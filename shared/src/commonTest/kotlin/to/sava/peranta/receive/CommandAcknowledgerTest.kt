package to.sava.peranta.receive

import kotlinx.coroutines.test.runTest
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.model.CommandAckPayload
import to.sava.peranta.model.CommandOutcome
import to.sava.peranta.model.CommandType
import to.sava.peranta.model.decodeEnvelope
import to.sava.peranta.net.FakeNtfyClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandAcknowledgerTest {

    private val cipher = MessageCipher(generateKey(), "k1")

    /** 応答は指定の topic へ封緘して送り、サーバに保持させる（cache = true）。 */
    @Test
    fun publishesSealedAckToTopicWithCache() = runTest {
        val ntfy = FakeNtfyClient()
        val ack = CommandAckPayload(
            id = "ack-1",
            from = "phone",
            to = "desk",
            sentAtEpochMillis = 1_000L,
            commandId = "cmd-1",
            command = CommandType.REPLY,
            outcome = CommandOutcome.EXECUTED,
        )

        publishingCommandAcknowledger(cipher, ntfy).acknowledge("ack-topic", ack)

        val published = ntfy.published.single()
        assertEquals("ack-topic", published.topic)
        assertTrue(published.cache)
        assertEquals(ack, cipher.open(decodeEnvelope(published.body)))
    }
}
