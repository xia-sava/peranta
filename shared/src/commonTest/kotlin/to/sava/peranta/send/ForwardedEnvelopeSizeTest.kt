package to.sava.peranta.send

import kotlinx.coroutines.test.runTest
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.filter.FilterMode
import to.sava.peranta.model.encodeEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForwardedEnvelopeSizeTest {

    /** ntfy 既定の message-size-limit（bytes）。 */
    private val ntfyMessageSizeLimit = 4096

    private fun cipher() = MessageCipher(generateKey(), "k1")

    private fun input(title: String, text: String) = NotificationInput(
        packageName = "com.example.chat",
        appName = "Chat",
        title = title,
        text = text,
        notificationKey = "0|com.example.chat|1|null|10",
        postedAtEpochMillis = 1000,
    )

    private fun build(title: String, text: String) = buildNotificationPayload(
        input(title = title, text = text),
        mode = FilterMode.DENYLIST,
        rules = emptyList(),
        deviceId = "phone",
        now = 2000,
    )!!

    /** 日本語 900 文字超の本文でも、封緘後の Envelope は ntfy 既定上限 4096 bytes に収まる。 */
    @Test
    fun longJapaneseBodyFitsWithinNtfyLimit() = runTest {
        val payload = build(title = "重要なお知らせ".repeat(50), text = "あ".repeat(1000))
        val body = encodeEnvelope(cipher().seal(payload))
        val size = body.encodeToByteArray().size
        assertTrue(size <= ntfyMessageSizeLimit, "envelope was $size bytes")
    }

    /** 絵文字を含む本文は転送時の切り詰めでサロゲートペアが壊れず、封緘・開封を往復できる。 */
    @Test
    fun emojiBodySurvivesForwarding() = runTest {
        val payload = build(title = "🎉", text = "🙂".repeat(2000))
        val core = payload.text.removeSuffix("…")
        assertTrue(core.isNotEmpty())
        assertEquals(0, core.length % "🙂".length)
        assertTrue(core.chunked("🙂".length).all { it == "🙂" })

        val cipher = cipher()
        val restored = cipher.open(cipher.seal(payload))
        assertTrue(restored.toString().contains("🙂"))
    }
}
