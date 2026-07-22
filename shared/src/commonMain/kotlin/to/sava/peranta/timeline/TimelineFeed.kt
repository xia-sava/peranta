package to.sava.peranta.timeline

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * プロセス内で共有するタイムラインの単一ソース（§10.1・§11）。
 * 永続化は [store] へ委譲し、追記を在メモリの [items] へ即時反映する。
 * [TimelineStore] を実装するため、既存の追記元（送受信パイプライン・再送ワーカー等）は
 * コンストラクタの型を変えずに、渡す実体をこの feed にするだけで即時反映が効く。
 */
class TimelineFeed(
    private val store: TimelineStore,
    private val log: Logger = Logger.withTag("TimelineFeed"),
) : TimelineStore {

    private val _items = MutableStateFlow<List<TimelineItem>>(emptyList())
    val items: StateFlow<List<TimelineItem>> = _items.asStateFlow()

    /** 永続化してから表示へ反映する。永続失敗は呼び出し側へ伝播し、表示へも載せない。 */
    override suspend fun append(item: TimelineItem) {
        store.append(item)
        upsert(item)
    }

    /**
     * 表示用と保存用を分けて追記する（受信の伏せ字系、§11）。
     * 永続失敗は握ってログに残し、表示は継続する。
     */
    suspend fun record(displayItem: TimelineItem, persistItem: TimelineItem = displayItem) {
        try {
            store.append(persistItem)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e { "failed to persist timeline item id=${displayItem.id} (${e::class.simpleName})" }
        }
        upsert(displayItem)
    }

    /**
     * 保存済み履歴で表示を初期化する。失効分は表示から除外し、同一 id は後勝ちで畳んで表示する
     * （再送等で同一 id が複数行になり得るため、§11）。稼働中に追記済みの在メモリのアイテムは
     * 履歴側より優先して残す（伏せ字前の表示や読込と並行した追記を、読込で巻き戻さない）。
     * 戻り値は畳む前の全履歴（dedupe 初期化用）。
     */
    suspend fun load(now: Long): List<TimelineItem> {
        val history = store.loadAll()
        val base = history.associateBy { it.id }.values
            .filterNot { it.expiresAtEpochMillis?.let { at -> at < now } ?: false }
        _items.update { current ->
            val currentById = current.associateBy { it.id }
            val baseIds = base.map { it.id }.toSet()
            base.map { currentById[it.id] ?: it } + current.filter { it.id !in baseIds }
        }
        return history
    }

    override suspend fun loadAll(): List<TimelineItem> = store.loadAll()

    /**
     * [store] の剪定を行う。稼働中の呼び出しでは [items] を自動で追い直さないため、
     * 起動時の一度きりの呼び出し（直後に [load] する運用）を前提とする。
     */
    override suspend fun prune(maxItems: Int, now: Long) = store.prune(maxItems, now)

    /** 同一 id は置換、初出は末尾追記。[MutableStateFlow.update] の CAS で並行追記に対して安全。 */
    private fun upsert(item: TimelineItem) {
        _items.update { current ->
            val index = current.indexOfFirst { it.id == item.id }
            if (index < 0) current + item else current.toMutableList().also { it[index] = item }
        }
    }
}
