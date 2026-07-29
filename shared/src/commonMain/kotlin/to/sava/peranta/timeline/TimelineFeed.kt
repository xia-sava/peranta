package to.sava.peranta.timeline

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 在メモリのタイムラインに載せる件数の上限。[TimelineStore.prune] の既定と揃える。 */
const val MAX_IN_MEMORY_TIMELINE_ITEMS: Int = 1000

/** 稼働中に [TimelineStore] の剪定を挟む追記件数の間隔。 */
private const val PRUNE_INTERVAL_APPENDS: Int = 200

/**
 * プロセス内で共有するタイムラインの単一ソース（§10.1・§11）。
 * 永続化は [store] へ委譲し、追記を在メモリの [items] へ即時反映する。
 * [TimelineStore] を実装するため、既存の追記元（送受信パイプライン・再送ワーカー等）は
 * コンストラクタの型を変えずに、渡す実体をこの feed にするだけで即時反映が効く。
 *
 * [items] は [maxItems] 件で頭打ちにし、超えた分は古い順に表示から落とす（§11）。
 * 永続側は起動時の [prune] だけでは稼働中に縮まないため、その呼び出しで受け取った条件を覚えておき、
 * 追記 [PRUNE_INTERVAL_APPENDS] 件ごとに同じ条件で剪定し直す。
 */
class TimelineFeed(
    private val store: TimelineStore,
    private val log: Logger = Logger.withTag("TimelineFeed"),
    private val maxItems: Int = MAX_IN_MEMORY_TIMELINE_ITEMS,
) : TimelineStore {

    private val _items = MutableStateFlow<List<TimelineItem>>(emptyList())
    val items: StateFlow<List<TimelineItem>> = _items.asStateFlow()

    /** 直近の [prune] で受け取った条件。null なら起動時の剪定をまだ通っておらず、稼働中も剪定しない。 */
    private var prunePolicy: PrunePolicy? = null

    private var appendsSincePrune = 0

    /** 稼働中の剪定を起動時と同じ条件で回すために覚えておく [prune] の引数。 */
    private data class PrunePolicy(val maxItems: Int, val maxAgeMillis: Long?)

    /** 永続化してから表示へ反映する。永続失敗は呼び出し側へ伝播し、表示へも載せない。 */
    override suspend fun append(item: TimelineItem) {
        store.append(item)
        upsert(item)
        prunePeriodically(item.timestampEpochMillis)
    }

    /**
     * 表示用と保存用を分けて追記する（受信の伏せ字系、§11）。
     * 永続失敗は握ってログに残し、表示は継続する。
     * 戻り値は同一 id が既存だったか（[upsert] 参照）。新規追記なら true、既存の置換なら false。
     */
    suspend fun record(displayItem: TimelineItem, persistItem: TimelineItem = displayItem): Boolean {
        try {
            store.append(persistItem)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e { "failed to persist timeline item id=${displayItem.id} (${e::class.simpleName})" }
        }
        return upsert(displayItem).also { prunePeriodically(displayItem.timestampEpochMillis) }
    }

    /**
     * 保存済み履歴で表示を初期化する。失効分は表示から除外し、同一 id は後勝ちで畳んで表示する
     * （再送等で同一 id が複数行になり得るため、§11）。稼働中に追記済みの在メモリのアイテムは
     * 履歴側より優先して残す（伏せ字前の表示や読込と並行した追記を、読込で巻き戻さない）。
     * 表示は追記時と同じく [maxItems] 件で頭打ちにする。
     * 戻り値は畳む前の全履歴（dedupe 初期化用）。
     */
    suspend fun load(now: Long): List<TimelineItem> {
        val history = store.loadAll()
        val base = history.associateBy { it.id }.values
            .filterNot { it.expiresAtEpochMillis?.let { at -> at < now } ?: false }
        _items.update { current ->
            val currentById = current.associateBy { it.id }
            val baseIds = base.map { it.id }.toSet()
            (base.map { currentById[it.id] ?: it } + current.filter { it.id !in baseIds }).takeLast(maxItems)
        }
        return history
    }

    override suspend fun loadAll(): List<TimelineItem> = store.loadAll()

    /**
     * [store] の剪定を行い、以後の稼働中の剪定に使う条件として覚える。
     * 稼働中の呼び出しでは [items] を自動で追い直さないため、起動時の一度きりの呼び出し
     * （直後に [load] する運用）を前提とする。
     */
    override suspend fun prune(maxItems: Int, now: Long, maxAgeMillis: Long?) {
        prunePolicy = PrunePolicy(maxItems, maxAgeMillis)
        appendsSincePrune = 0
        store.prune(maxItems, now, maxAgeMillis)
    }

    /**
     * 追記が一定件数に達するたび、起動時と同じ条件で [store] を剪定し直す。
     * 起動時の [prune] を通っていなければ何もしない。剪定の失敗は握ってログに残し、追記は継続する。
     */
    private suspend fun prunePeriodically(now: Long) {
        val policy = prunePolicy ?: return
        if (++appendsSincePrune < PRUNE_INTERVAL_APPENDS) return
        appendsSincePrune = 0
        try {
            store.prune(policy.maxItems, now, policy.maxAgeMillis)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w { "failed to prune timeline store (${e::class.simpleName})" }
        }
    }

    /**
     * 同一 id は置換、初出は末尾追記。[MutableStateFlow.update] の CAS で並行追記に対して安全。
     * 追記で [maxItems] を超えた分は古い順に落とし、在メモリのタイムラインを頭打ちにする。
     * 戻り値は初出（末尾追記）なら true、既存の置換なら false。
     */
    private fun upsert(item: TimelineItem): Boolean {
        var appended = false
        _items.update { current ->
            val index = current.indexOfFirst { it.id == item.id }
            if (index < 0) {
                appended = true
                (current + item).takeLast(maxItems)
            } else {
                appended = false
                current.toMutableList().also { it[index] = item }
            }
        }
        return appended
    }
}
