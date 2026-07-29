package to.sava.peranta.roster

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.model.PresencePayload
import to.sava.peranta.model.decodeEnvelope
import to.sava.peranta.net.NtfyClient
import to.sava.peranta.net.NtfyEvent

/** control topic 履歴取得の打ち切り時間枠（ミリ秒）。即時送信を長時間ブロックしないための上限。 */
const val ROSTER_FETCH_TIMEOUT_MILLIS: Long = 8_000L

/**
 * [RosterStore.fetch] の結果。取得自体の失敗と「取得できたが端末が 0 件」を区別する（§8）。
 * 前者を後者と混同すると、fetch 失敗時に静的な配送先へ無自覚にフォールバックしてしまう。
 */
sealed class RosterFetchResult {

    /** 履歴取得・復号に成功した（[entries] が 0 件でもよい）。 */
    class Fetched(val entries: List<RosterEntry>) : RosterFetchResult()

    /** 履歴取得自体がタイムアウト・ネットワーク断・認証エラー等で失敗した。解決不能として扱う。 */
    object FetchFailed : RosterFetchResult()
}

/**
 * control topic のメッセージ履歴からロスターを構築する（§3.5）。
 * 永続接続を持たない送信端末でも、送信時に [fetch] を呼んで最新のロスターを得られる。
 * 復号できない・presence でないメッセージは 1 件ずつ握って読み飛ばし、
 * 取得できた presence だけからロスターを組む。
 */
class RosterStore(
    private val ntfy: NtfyClient,
    private val cipher: MessageCipher,
    private val controlTopic: String,
    private val log: Logger = Logger.withTag("Roster"),
    private val fetchTimeoutMillis: Long = ROSTER_FETCH_TIMEOUT_MILLIS,
) {

    /** control topic の履歴を取得・復号してロスターを返す。取得に失敗・タイムアウトしたら [RosterFetchResult.FetchFailed]。 */
    suspend fun fetch(): RosterFetchResult {
        val events = fetchHistoryOrNull() ?: return RosterFetchResult.FetchFailed
        val presences = events.mapNotNull { presenceOrNull(it) }
        log.d { "roster built from ${presences.size}/${events.size} presence messages" }
        val entries = buildRoster(presences)
        log.d { "roster: ${entries.joinToString(separator = "; ") { describe(it) }}" }
        return RosterFetchResult.Fetched(entries)
    }

    /**
     * 診断ログ向けにエントリを 1 行で表す。宛先が引けない原因は deviceId の不一致かエンドポイントの
     * 欠落に絞られるため、その 2 点とコマンドを実行できるかを出す。エンドポイントは購読先そのもの
     * なので、値ではなく有無だけを残す。
     */
    private fun describe(entry: RosterEntry): String =
        "${entry.deviceId}(${entry.deviceName}) " +
            "endpoint=${if (topicOf(entry.endpoint).isBlank()) "none" else "set"} " +
            "capabilities=${entry.capabilities.joinToString(",").ifEmpty { "none" }} " +
            "sender=${entry.sender}"

    /** 履歴取得を [fetchTimeoutMillis] で打ち切りつつ実行する。失敗・タイムアウトは null。 */
    private suspend fun fetchHistoryOrNull(): List<NtfyEvent>? =
        try {
            withTimeoutOrNull(fetchTimeoutMillis) { ntfy.fetchHistory(controlTopic) }
                ?: run {
                    log.w { "control topic history fetch timed out after ${fetchTimeoutMillis}ms" }
                    null
                }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log.w(error) { "failed to fetch control topic history" }
            null
        }

    /** 1 件の履歴イベントを復号し、presence なら取り出す。それ以外・失敗は null。 */
    private suspend fun presenceOrNull(event: NtfyEvent): PresencePayload? =
        try {
            decodeEnvelope(event.message)
                .let { cipher.open(it) }
                .let { it as? PresencePayload }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log.d { "skipping undecodable control message (${error::class.simpleName})" }
            null
        }
}
