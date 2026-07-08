package to.sava.peranta.roster

import co.touchlab.kermit.Logger
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.PresencePayload
import to.sava.peranta.model.encodeEnvelope
import to.sava.peranta.model.newPayloadId
import to.sava.peranta.net.NtfyClient

/** display（受信表示）能力を表す capability 文字列。 */
const val CAPABILITY_DISPLAY: String = "display"

/** command（逆方向コマンド）能力を表す capability 文字列。 */
const val CAPABILITY_COMMAND: String = "command"

/**
 * 自端末の presence を組み立てる（§4.1）。
 * from に安定 ID（[deviceId]）を載せて識別に使い、[deviceName] は表示名として持つ。
 */
fun buildPresencePayload(
    deviceId: String,
    deviceName: String,
    endpoint: String,
    capabilities: List<String>,
    sender: Boolean,
    now: Long,
    idGen: () -> String = ::newPayloadId,
): PresencePayload = PresencePayload(
    id = idGen(),
    from = deviceId,
    to = BROADCAST_TARGET,
    sentAtEpochMillis = now,
    deviceName = deviceName,
    endpoint = endpoint,
    capabilities = capabilities,
    sender = sender,
)

/**
 * presence を暗号化して control topic へ告知する（§3.5）。
 * control topic はロスター永続のため長めのキャッシュに委ねるので、
 * キャッシュ短縮ヘッダは付けない（サーバ既定の保持時間を使う）。
 */
suspend fun publishPresence(
    cipher: MessageCipher,
    ntfy: NtfyClient,
    controlTopic: String,
    presence: PresencePayload,
    log: Logger = Logger.withTag("Presence"),
) {
    val body = encodeEnvelope(cipher.seal(presence))
    ntfy.publish(controlTopic, body, cacheSeconds = null)
    log.d { "presence announced to $controlTopic for device=${presence.from}" }
}
