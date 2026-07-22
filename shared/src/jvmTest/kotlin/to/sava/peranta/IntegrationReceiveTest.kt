package to.sava.peranta

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Priority
import to.sava.peranta.model.encodeEnvelope
import to.sava.peranta.model.newPayloadId
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.net.KtorNtfyClient
import to.sava.peranta.net.NtfyConnectionState
import to.sava.peranta.net.createNtfyHttpClient
import to.sava.peranta.receive.ReceivePipeline
import to.sava.peranta.timeline.FileTimelineFile
import to.sava.peranta.timeline.JsonlTimelineStore
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineFeed
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 実ローカル ntfy（localhost:8090）に対する e2e。PERANTA_IT=1 のときだけ実行する。
 * 鍵生成 → seal → publish → subscribe → ReceivePipeline → JSONL 着地までを検証する。
 */
class IntegrationReceiveTest {

    @Test
    fun endToEndThroughLocalNtfy() {
        assumeTrue("PERANTA_IT!=1 のためスキップ", System.getenv("PERANTA_IT") == "1")
        val token = readToken() ?: error("server/.local/credentials.txt の token= が見つかりません")

        runBlocking {
            val httpClient = createNtfyHttpClient()
            try {
                val keyBytes = generateKey()
                val topic = "peranta-debug-it-" + randomSuffix()
                val config = PerantaConfig(
                    host = "localhost",
                    useTls = false,
                    port = 8090,
                    accessToken = token,
                    deviceName = "it-desk",
                    sharedKeyBase64 = Base64.encode(keyBytes),
                    keyId = "k1",
                    receiveTopic = topic,
                )
                val cipher = MessageCipher(keyBytes, "k1")
                val tempFile = File.createTempFile("peranta-it", ".jsonl").also { it.deleteOnExit() }
                val store = JsonlTimelineStore(FileTimelineFile(tempFile))
                val ntfy = KtorNtfyClient(config, httpClient)
                val pipeline = ReceivePipeline(ntfy, cipher, TimelineFeed(store), "it-desk")

                val subscription = launch { pipeline.start(topic) }
                withTimeout(10_000) {
                    ntfy.connectionState.first { it == NtfyConnectionState.SUBSCRIBED }
                }

                val payload = NotificationPayload(
                    id = newPayloadId(),
                    from = "it-sender",
                    to = "*",
                    sentAtEpochMillis = nowEpochMillis(),
                    packageName = "com.example.bank",
                    appName = "Bank",
                    title = "Verification",
                    text = "code 246810",
                    notificationKey = "0|com.example.bank|9|null|10",
                    postedAtEpochMillis = nowEpochMillis(),
                    priority = Priority.HIGH,
                )
                ntfy.publish(topic, encodeEnvelope(cipher.seal(payload)), cacheSeconds = 30)

                withTimeout(15_000) {
                    while (pipeline.items.value.isEmpty()) delay(100)
                }
                subscription.cancel()

                val received = pipeline.items.value.single() as ReceivedNotification
                assertEquals(payload.id, received.id)
                assertEquals(payload, received.payload)

                val stored = store.loadAll()
                assertEquals(1, stored.size)
                assertEquals(payload.id, stored.single().id)
            } finally {
                httpClient.close()
            }
        }
    }

    private fun randomSuffix(): String =
        buildString { repeat(12) { append("abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)]) } }

    /** カレントから上位へ辿って server/.local/credentials.txt の token= を読む。 */
    private fun readToken(): String? {
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(5) {
            val candidate = dir?.let { File(it, "server/.local/credentials.txt") }
            if (candidate != null && candidate.exists()) {
                return candidate.readLines()
                    .firstOrNull { it.startsWith("token=") }
                    ?.substringAfter("token=")
                    ?.trim()
            }
            dir = dir?.parentFile
        }
        return null
    }
}
