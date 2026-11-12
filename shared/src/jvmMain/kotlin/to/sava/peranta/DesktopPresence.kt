package to.sava.peranta

import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.model.PresencePayload
import to.sava.peranta.net.httpBaseUrl
import to.sava.peranta.roster.PresenceSource

/**
 * Desktop の設定から、presence の告知に載せる自端末の情報を組む（§3.5）。
 * control topic・deviceId・受信 topic のいずれかが未設定なら null。端末名が未設定なら deviceId を表示名に使う。
 * エンドポイントはサーバのベース URL に受信 topic を連ねたもの。
 * 受信中は通知を表示できる。元通知への操作は通知捕捉（NLS）を持たない Desktop では実行できないため
 * コマンド能力は持たない（自表示通知の取り下げは表示能力に含む、§3.5）。
 * 返す [PresenceSource.publish] は、設定の control topic と presence を [publish] へ渡す。
 */
internal fun desktopPresenceSource(
    config: PerantaConfig,
    publish: suspend (controlTopic: String, presence: PresencePayload) -> Unit,
): PresenceSource? {
    val controlTopic = config.controlTopic ?: return null
    val deviceId = config.deviceId ?: return null
    val receiveTopic = config.receiveTopic ?: return null
    return PresenceSource(
        deviceId = deviceId,
        deviceName = config.deviceName ?: deviceId,
        endpoint = "${config.httpBaseUrl()}/$receiveTopic",
        canDisplay = true,
        canCommand = false,
        sender = config.sendEnabled,
        publish = { presence -> publish(controlTopic, presence) },
    )
}
