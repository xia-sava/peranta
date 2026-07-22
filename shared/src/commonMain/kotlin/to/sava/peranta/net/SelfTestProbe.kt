package to.sava.peranta.net

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import to.sava.peranta.model.newPayloadId
import to.sava.peranta.model.nowEpochMillis
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 自己疎通テストのマーカー接頭辞。これで始まるメッセージは受信パイプラインに入れない。 */
const val SELF_TEST_MARKER_PREFIX: String = "peranta-selftest:"

/** マーカー到達を待つ時間。ntfy の即時配信は通常 1〜2 秒で届く。 */
val SELF_TEST_TIMEOUT: Duration = 5.seconds

/** マーカーのキャッシュ保持秒数（サーバに残す時間の上限）。 */
const val SELF_TEST_CACHE_SECONDS: Int = 60

/** 1 回のテストの結末。 */
sealed interface SelfTestResult {
    data object Delivered : SelfTestResult
    data class PublishRejected(val status: Int) : SelfTestResult
    data object PublishFailed : SelfTestResult
    data object Timeout : SelfTestResult
}

/** プローブの現在状態。動作チェックの項目描画がこれを読む。 */
sealed interface SelfTestStatus {
    data object NotRun : SelfTestStatus
    data object Running : SelfTestStatus
    data class Done(val result: SelfTestResult, val atEpochMillis: Long) : SelfTestStatus
}

/**
 * 自己疎通テストのプローブ中核。自分のエンドポイント topic へ平文マーカーを publish し、
 * UnifiedPush の onMessage で横取りされたマーカーの到達を待って結果を分類する。
 * 復号・タイムライン追記・OS 通知は一切経由しない。
 */
class SelfTestProbe(
    private val timeout: Duration = SELF_TEST_TIMEOUT,
    private val nonceGen: () -> String = ::newPayloadId,
    private val now: () -> Long = ::nowEpochMillis,
    private val log: Logger = Logger.withTag("SelfTest"),
) {

    private val _status = MutableStateFlow<SelfTestStatus>(SelfTestStatus.NotRun)
    val status: StateFlow<SelfTestStatus> = _status.asStateFlow()

    private class Pending(val nonce: String, val arrival: CompletableDeferred<Unit>)

    @Volatile
    private var pending: Pending? = null

    /**
     * テストを 1 回実行する。実行中に再度呼ばれた場合は開始せず、状態を壊さないまま
     * 直近の完了結果（無ければ [SelfTestResult.Timeout]）を返す。
     */
    suspend fun run(ntfy: NtfyClient, topic: String): SelfTestResult {
        val previous = _status.value
        if (previous is SelfTestStatus.Running || !_status.compareAndSet(previous, SelfTestStatus.Running)) {
            return (_status.value as? SelfTestStatus.Done)?.result ?: SelfTestResult.Timeout
        }

        val nonce = nonceGen()
        val arrival = CompletableDeferred<Unit>()
        pending = Pending(nonce, arrival)

        try {
            val result = try {
                ntfy.publish(topic, SELF_TEST_MARKER_PREFIX + nonce, cacheSeconds = SELF_TEST_CACHE_SECONDS)
                if (withTimeoutOrNull(timeout) { arrival.await() } != null) {
                    SelfTestResult.Delivered
                } else {
                    SelfTestResult.Timeout
                }
            } catch (rejected: NtfyPublishException) {
                SelfTestResult.PublishRejected(rejected.status)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                log.w(error) { "self-test publish failed" }
                SelfTestResult.PublishFailed
            }
            _status.value = SelfTestStatus.Done(result, now())
            return result
        } finally {
            pending = null
            // キャンセル等で Done を設定できずに抜けた場合、Running のまま固着させず元の状態へ戻す。
            if (_status.value is SelfTestStatus.Running) {
                _status.value = previous
            }
        }
    }

    /**
     * 受信メッセージがマーカーなら常に true を返す（呼び出し側が破棄する）。
     * 待機中のマーカーと nonce が一致すれば到達として完了させる。マーカーでなければ false。
     */
    fun consumeMarker(rawMessage: String): Boolean {
        if (!rawMessage.startsWith(SELF_TEST_MARKER_PREFIX)) return false
        val nonce = rawMessage.removePrefix(SELF_TEST_MARKER_PREFIX)
        val current = pending
        if (current != null && current.nonce == nonce) {
            current.arrival.complete(Unit)
            log.d { "self-test marker matched" }
        } else {
            log.d { "self-test marker discarded (no matching pending)" }
        }
        return true
    }
}
