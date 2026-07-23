package to.sava.peranta

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import to.sava.peranta.blob.drainToBytes
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.model.FilePayload
import to.sava.peranta.model.MessagePayload
import to.sava.peranta.net.FakeNtfyClient
import to.sava.peranta.send.FakeTimelineStore
import to.sava.peranta.send.SendPipeline
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.SentNotification
import java.io.File
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Desktop composer のファイル送信束（[DesktopComposer]、§13 M9d）を検証する。
 * ファイルダイアログは開かず、[DesktopComposer.addStaged] で直接ステージへ積む。
 * blob アップロード先は実 HTTP ではなく [MockEngine] で模擬する。
 */
class DesktopComposerTest {

    private val tempFiles = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.delete() }
    }

    private fun tempFile(name: String, content: String): File {
        val file = Files.createTempFile(name, ".bin").toFile()
        file.writeText(content)
        tempFiles.add(file)
        return file
    }

    private fun config(blobTopic: String? = "blob-topic") = PerantaConfig(
        deviceId = "desktop-1",
        deviceName = "デスクトップ",
        sharedKeyBase64 = Base64.encode(generateKey()),
        keyId = "k1",
        blobTopic = blobTopic,
        deliveryTopics = listOf("topic-a"),
    )

    private fun successHttpClient(captured: MutableList<HttpRequestData> = mutableListOf()): HttpClient =
        HttpClient(
            MockEngine { request ->
                captured.add(request)
                // 実クライアントは送信中に body を書き出す。MockEngine はそれを模擬しないため、
                // 暗号化コールバック（BlobCipher.encrypt）が走るよう明示的に書き出させる。
                (request.body as? OutgoingContent.WriteChannelContent)?.let { content ->
                    drainToBytes { content.writeTo(it) }
                }
                respond(
                    content = """{"attachment":{"url":"https://blob.invalid/${captured.size}","expires":9000}}""",
                    status = HttpStatusCode.OK,
                )
            },
        )

    private fun failingHttpClient(): HttpClient =
        HttpClient(MockEngine { respond(content = "", status = HttpStatusCode.Forbidden) })

    /** ステージ済みファイルが有る送信は、ファイル数ぶんアップロードして 1 つの FilePayload に束ねて publish する。 */
    @Test
    fun sendWithStagedFilesUploadsAndPublishesFilePayload() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val store = FakeTimelineStore()
        val pipeline = SendPipeline(MessageCipher(generateKey(), "k1"), FakeNtfyClient(), store)
        val composer = DesktopComposer(
            config = config(),
            httpClient = successHttpClient(captured),
            cipher = MessageCipher(generateKey(), "k1"),
            ntfy = FakeNtfyClient(),
            sendPipeline = pipeline,
            scope = this,
        )
        composer.addStaged(listOf(tempFile("a", "file-a"), tempFile("b", "file-b")))

        val delivered = composer.ui().send("キャプション")

        assertTrue(delivered)
        assertEquals(2, captured.size)
        val sent = store.appended.single() as SentNotification
        val payload = sent.payload as FilePayload
        assertEquals("キャプション", payload.caption)
        assertEquals(2, payload.attachments.size)
        assertEquals("デスクトップ", payload.fromName)
        assertTrue(composer.ui().attachments!!.staged.value.isEmpty())
    }

    /** ステージが空の送信はメッセージ送信（[to.sava.peranta.send.sendMessage]）に委譲し、MessagePayload が publish される。 */
    @Test
    fun sendWithoutStagedFilesSendsMessagePayload() = runTest {
        val store = FakeTimelineStore()
        val pipeline = SendPipeline(MessageCipher(generateKey(), "k1"), FakeNtfyClient(), store)
        val composer = DesktopComposer(
            config = config(),
            httpClient = successHttpClient(),
            cipher = MessageCipher(generateKey(), "k1"),
            ntfy = FakeNtfyClient(),
            sendPipeline = pipeline,
            scope = this,
        )

        val delivered = composer.ui().send("こんにちは")

        assertTrue(delivered)
        val sent = store.appended.single() as SentNotification
        assertEquals("こんにちは", (sent.payload as MessagePayload).text)
    }

    /** アップロード失敗時は ErrorItem を記録して false を返し、ステージ済みファイルは保持する。 */
    @Test
    fun sendFailureRecordsErrorAndKeepsStaged() = runTest {
        val store = FakeTimelineStore()
        val pipeline = SendPipeline(MessageCipher(generateKey(), "k1"), FakeNtfyClient(), store)
        val composer = DesktopComposer(
            config = config(),
            httpClient = failingHttpClient(),
            cipher = MessageCipher(generateKey(), "k1"),
            ntfy = FakeNtfyClient(),
            sendPipeline = pipeline,
            scope = this,
        )
        composer.addStaged(listOf(tempFile("c", "file-c")))

        val delivered = composer.ui().send("本文")

        assertFalse(delivered)
        assertEquals(FILE_SEND_FAILED_MESSAGE, (store.appended.single() as ErrorItem).message)
        assertEquals(1, composer.ui().attachments!!.staged.value.size)
    }

    /** removeStaged は該当 index のファイルだけをステージから外す。 */
    @Test
    fun removeStagedRemovesOnlyThatFile() = runTest {
        val store = FakeTimelineStore()
        val pipeline = SendPipeline(MessageCipher(generateKey(), "k1"), FakeNtfyClient(), store)
        val composer = DesktopComposer(
            config = config(),
            httpClient = successHttpClient(),
            cipher = MessageCipher(generateKey(), "k1"),
            ntfy = FakeNtfyClient(),
            sendPipeline = pipeline,
            scope = this,
        )
        val first = tempFile("d", "file-d")
        val second = tempFile("e", "file-e")
        composer.addStaged(listOf(first, second))

        composer.ui().attachments!!.removeStaged(0)

        val remaining = composer.ui().attachments!!.staged.value
        assertEquals(1, remaining.size)
        assertEquals(second.name, remaining.single().name)
    }
}
