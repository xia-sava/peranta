package to.sava.peranta.roster

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import to.sava.peranta.model.PresencePayload
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.platform.failureForLog

/** 告知に載せる自端末の情報と、control topic へ publish する関数（§3.5）。 */
class PresenceSource(
    val deviceId: String,
    val deviceName: String,
    val endpoint: String,
    val canDisplay: Boolean,
    val canCommand: Boolean,
    val sender: Boolean,
    /**
     * publish の届け先を見分ける値（control topic と keyId）。
     * 届け先が変わると、同じ内容でも他端末にはまだ届いていないため、抑止せずに告知する。
     */
    val destination: String,
    /** control topic へ publish する。抑止されたときは呼ばれない。 */
    val publish: suspend (PresencePayload) -> Unit,
)

/**
 * control topic へ自端末の presence を告知する（§3.5）。
 * 契機: 起動時、エンドポイントの確定時、通知捕捉（NLS）の接続と切断、通知の転送、アイテムの受信。
 */
class PresenceAnnouncer(
    scope: CoroutineScope,
    /** 告知できる状態なら、告知に載せる自端末の情報を返す。設定が足りなければ null。 */
    private val source: suspend () -> PresenceSource?,
    private val gate: PresenceAnnounceGate = PresenceAnnounceGate(),
    private val now: () -> Long = ::nowEpochMillis,
    debounceMillis: Long = PRESENCE_REANNOUNCE_DEBOUNCE_MILLIS,
    private val log: Logger = Logger.withTag("Presence"),
) {
    // gate はスレッド安全ではないため、抑止の判定から記録までをこの Mutex で直列化する。
    private val mutex = Mutex()
    private val scheduler = PresenceAnnounceScheduler<Unit>(scope, debounceMillis) { announce() }

    /** 続く契機を 1 回にまとめて告知する。呼び出し元のスレッドを選ばない。 */
    fun request() {
        scheduler.request(Unit)
    }

    /**
     * その場で告知する。呼び出しは直列化し、同じ届け先への同じ内容の連続は最小間隔で抑止する。
     * 失敗はログに残して呼び出し元へ伝えない。publish に失敗した内容は記録せず、次の契機で送り直す。
     */
    suspend fun announce() {
        mutex.withLock {
            try {
                announceLocked()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // 例外そのものは流さない。ktor の例外メッセージには接続先 URL（＝完全な topic）が載る（§11）。
                log.w { "presence announce failed (${failureForLog(error)})" }
            }
        }
    }

    private suspend fun announceLocked() {
        val current = source() ?: return
        val presence = buildPresencePayload(
            deviceId = current.deviceId,
            deviceName = current.deviceName,
            endpoint = current.endpoint,
            capabilities = presenceCapabilities(canDisplay = current.canDisplay, canCommand = current.canCommand),
            sender = current.sender,
            now = now(),
        )
        val fingerprint = "${presenceFingerprint(presence)}|${current.destination}"
        // 抑止されるときは通信の支度もしない。通知の転送ごとに呼ばれるため、空振りを安く済ませる。
        if (!gate.shouldAnnounce(fingerprint, presence.sentAtEpochMillis)) {
            log.d { "presence unchanged; skipping announce" }
            return
        }
        current.publish(presence)
        gate.recordAnnounced(fingerprint, presence.sentAtEpochMillis)
    }
}
