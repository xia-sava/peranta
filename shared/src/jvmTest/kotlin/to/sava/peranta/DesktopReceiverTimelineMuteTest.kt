package to.sava.peranta

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.filter.RuleAction
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.toast.NoOpToaster
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 受信専用端末のタイムラインからの mute（§3.4/§10.1）が、送信元へのコマンド送信だけでなく
 * 自端末のローカルミラー（filterRules）も更新することを検証する。
 * control topic を未設定にして、コマンド送信が実ネットワークへ出る前に宛先解決で打ち切られるようにする
 * （[to.sava.peranta.send.CommandSender.muteApp] は宛先 topic が解決できない場合 publish を行わない）。
 */
class DesktopReceiverTimelineMuteTest {

    private fun testConfig(): PerantaConfig {
        val keyBytes = generateKey()
        return PerantaConfig(
            deviceId = "recv-device",
            sharedKeyBase64 = Base64.encode(keyBytes),
            keyId = "k1",
            controlTopic = null,
        )
    }

    private fun testPayload(packageName: String, from: String) = NotificationPayload(
        id = "n1",
        from = from,
        to = "*",
        sentAtEpochMillis = 1L,
        packageName = packageName,
        appName = packageName,
        title = "title",
        text = "text",
        notificationKey = "key",
        postedAtEpochMillis = 1L,
    )

    /** タイムラインの mute 操作は denylist の除外ルールとして自端末のローカルミラーへも反映される。 */
    @Test
    fun muteFromTimelineUpdatesLocalMirror() {
        val repository = ConfigRepository(MapSettings())
        val receiver = DesktopReceiver(testConfig(), repository, toaster = NoOpToaster)
        try {
            receiver.timelineActions().muteApp(testPayload("com.spam", from = "phone-1"))

            val rules = repository.load().filterRules
            assertEquals(1, rules.size)
            assertEquals("com.spam", rules[0].packageName)
            assertEquals(RuleAction.EXCLUDE, rules[0].action)
        } finally {
            runBlocking { receiver.close() }
        }
    }
}
