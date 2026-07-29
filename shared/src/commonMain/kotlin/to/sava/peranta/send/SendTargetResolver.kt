package to.sava.peranta.send

import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.net.NtfyClient
import to.sava.peranta.roster.RosterStore
import to.sava.peranta.roster.resolveDeliveryTopics

/**
 * `to: "*"` の配送先 topic を解決する（§8）。即時送信と WorkManager 再送の双方が使う。
 * control topic があればロスターから自分以外のエンドポイントへ fan-out し、
 * ロスターが取得できて空・control topic 未設定なら静的な配送先 topic（[PerantaConfig.deliveryTopics]）へ
 * 退避する。ロスター取得自体が失敗したときは解決不能とみなし、静的フォールバックへは流さず空を返す。
 * 空は「あとで解決され得る一時状態」であり、呼び出し側は送信済みとせず再送へ回す。
 */
suspend fun resolveSendTopics(
    config: PerantaConfig,
    cipher: MessageCipher,
    ntfy: NtfyClient,
): List<String> {
    val controlTopic = config.controlTopic ?: return config.deliveryTopics
    val result = RosterStore(ntfy, cipher, controlTopic).fetch()
    return resolveDeliveryTopics(result, config.deviceId, config.deliveryTopics)
}
