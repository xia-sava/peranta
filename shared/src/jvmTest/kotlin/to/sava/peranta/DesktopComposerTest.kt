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
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Desktop composer のファイル送信束（[DesktopComposer]、§13 M9d）を検証する。
 * ファイルダイアログは開かず、[DesktopComposer.addStaged] で直接ステージへ積む。
 * blob アップロード先は実 HTTP ではなく [MockEngine] で模擬する。
 */
class DesktopComposerTest {

    private val tempFiles = mutableListOf<File>()

    /** 貼り付け画像の置き場。実際のデータ領域（%APPDATA%\Peranta\clipboard）は触らない。 */
    private val clipboardRoot: File = Files.createTempDirectory("peranta-clipboard-test").toFile()

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.delete() }
        clipboardRoot.deleteRecursively()
    }

    private fun tempFile(name: String, content: String): File {
        val file = Files.createTempFile(name, ".bin").toFile()
        file.writeText(content)
        tempFiles.add(file)
        return file
    }

    private fun tempImageFile(name: String, width: Int = 40, height: Int = 20): File {
        val file = Files.createTempFile(name, ".png").toFile()
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", file)
        tempFiles.add(file)
        return file
    }

    private fun tempCorruptImageFile(name: String): File {
        val file = Files.createTempFile(name, ".png").toFile()
        file.writeText("not a real image")
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

    /** クリップボード一時ファイル名は 1 始まりの連番で機械的に決まる。 */
    @Test
    fun clipboardImageFileNameIsSequential() {
        assertEquals("clipboard-1.png", clipboardImageFileName(1))
        assertEquals("clipboard-2.png", clipboardImageFileName(2))
    }

    /** stageClipboardImage は画像を PNG ファイルへ書き出し、連番のファイル名で既存のステージへ積む。 */
    @Test
    fun stageClipboardImageWritesPngAndStagesSequentially() = runTest {
        val store = FakeTimelineStore()
        val pipeline = SendPipeline(MessageCipher(generateKey(), "k1"), FakeNtfyClient(), store)
        val composer = DesktopComposer(
            config = config(),
            httpClient = successHttpClient(),
            cipher = MessageCipher(generateKey(), "k1"),
            ntfy = FakeNtfyClient(),
            sendPipeline = pipeline,
            scope = this,
            clipboardImagesRoot = clipboardRoot,
        )
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)

        val first = composer.stageClipboardImage(image)
        val second = composer.stageClipboardImage(image)

        assertEquals("clipboard-1.png", first.name)
        assertEquals("clipboard-2.png", second.name)
        assertEquals(
            "png",
            ImageIO.getImageReaders(ImageIO.createImageInputStream(first)).next().formatName.lowercase(),
        )
        val staged = composer.ui().attachments!!.staged.value
        assertEquals(listOf("clipboard-1.png", "clipboard-2.png"), staged.map { it.name })
    }

    /** 貼り付け画像はアプリのデータ領域（消去の対象）の下に置き、%TEMP% には残さない。 */
    @Test
    fun stageClipboardImageWritesUnderTheGivenRoot() = runTest {
        val store = FakeTimelineStore()
        val pipeline = SendPipeline(MessageCipher(generateKey(), "k1"), FakeNtfyClient(), store)
        val composer = DesktopComposer(
            config = config(),
            httpClient = successHttpClient(),
            cipher = MessageCipher(generateKey(), "k1"),
            ntfy = FakeNtfyClient(),
            sendPipeline = pipeline,
            scope = this,
            clipboardImagesRoot = clipboardRoot,
        )

        val staged = composer.stageClipboardImage(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB))

        assertTrue(staged.canonicalPath.startsWith(clipboardRoot.canonicalPath))
    }

    /** ステージから外した貼り付け画像はその場で消える。利用者が選んだファイルは外しても消さない。 */
    @Test
    fun removeStagedDeletesPastedImageButKeepsPickedFile() = runTest {
        val store = FakeTimelineStore()
        val pipeline = SendPipeline(MessageCipher(generateKey(), "k1"), FakeNtfyClient(), store)
        val composer = DesktopComposer(
            config = config(),
            httpClient = successHttpClient(),
            cipher = MessageCipher(generateKey(), "k1"),
            ntfy = FakeNtfyClient(),
            sendPipeline = pipeline,
            scope = this,
            clipboardImagesRoot = clipboardRoot,
        )
        val picked = tempImageFile("picked")
        val pasted = composer.stageClipboardImage(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB))
        composer.addStaged(listOf(picked))

        composer.ui().attachments!!.removeStaged(0)
        composer.ui().attachments!!.removeStaged(0)

        assertFalse(pasted.exists())
        assertTrue(picked.exists())
    }

    /** 送信に成功したらステージが空になり、貼り付け画像の平文コピーもディスクに残らない。 */
    @Test
    fun successfulSendDeletesPastedImage() = runTest {
        val store = FakeTimelineStore()
        val pipeline = SendPipeline(MessageCipher(generateKey(), "k1"), FakeNtfyClient(), store)
        val composer = DesktopComposer(
            config = config(),
            httpClient = successHttpClient(),
            cipher = MessageCipher(generateKey(), "k1"),
            ntfy = FakeNtfyClient(),
            sendPipeline = pipeline,
            scope = this,
            clipboardImagesRoot = clipboardRoot,
        )
        val pasted = composer.stageClipboardImage(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB))

        assertTrue(composer.ui().send("キャプション"))

        assertFalse(pasted.exists())
    }

    /** 送信に失敗したときはステージを保つので、貼り付け画像も再送のために残す。 */
    @Test
    fun failedSendKeepsPastedImage() = runTest {
        val store = FakeTimelineStore()
        val pipeline = SendPipeline(MessageCipher(generateKey(), "k1"), FakeNtfyClient(), store)
        val composer = DesktopComposer(
            config = config(),
            httpClient = failingHttpClient(),
            cipher = MessageCipher(generateKey(), "k1"),
            ntfy = FakeNtfyClient(),
            sendPipeline = pipeline,
            scope = this,
            clipboardImagesRoot = clipboardRoot,
        )
        val pasted = composer.stageClipboardImage(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB))

        assertFalse(composer.ui().send("キャプション"))

        assertTrue(pasted.exists())
    }

    /** isImageFile は拡張子（大文字小文字を区別しない）で画像ファイルを判定する。 */
    @Test
    fun isImageFileDetectsCommonImageExtensionsCaseInsensitively() {
        assertTrue(isImageFile(File("photo.png")))
        assertTrue(isImageFile(File("photo.JPG")))
        assertTrue(isImageFile(File("photo.jpeg")))
        assertTrue(isImageFile(File("photo.gif")))
        assertTrue(isImageFile(File("photo.webp")))
        assertTrue(isImageFile(File("photo.bmp")))
        assertFalse(isImageFile(File("document.pdf")))
        assertFalse(isImageFile(File("noextension")))
    }

    /** scaledToFit は目標一辺を超える画像を縦横比を保ったまま縮小する。 */
    @Test
    fun scaledToFitShrinksOversizedImageKeepingAspectRatio() {
        val original = BufferedImage(400, 200, BufferedImage.TYPE_INT_ARGB)

        val scaled = original.scaledToFit(64)

        assertEquals(64, scaled.width)
        assertEquals(32, scaled.height)
    }

    /** scaledToFit は目標一辺以下の画像を拡大せずそのまま返す。 */
    @Test
    fun scaledToFitLeavesSmallImageUnchanged() {
        val original = BufferedImage(20, 10, BufferedImage.TYPE_INT_ARGB)

        val scaled = original.scaledToFit(64)

        assertEquals(20, scaled.width)
        assertEquals(10, scaled.height)
    }

    /** ステージした画像ファイルは縮小デコードされたサムネイルを持つ。 */
    @Test
    fun addStagedImageFileProducesThumbnail() = runTest {
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

        composer.addStaged(listOf(tempImageFile("f")))

        val staged = composer.ui().attachments!!.staged.value
        assertNotNull(staged.single().thumbnail)
    }

    /** ステージした非画像ファイルはサムネイルを持たず、従来どおりチップ表示のみとなる。 */
    @Test
    fun addStagedNonImageFileHasNoThumbnail() = runTest {
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

        composer.addStaged(listOf(tempFile("g", "not an image")))

        val staged = composer.ui().attachments!!.staged.value
        assertNull(staged.single().thumbnail)
    }

    /** デコードできない（拡張子は画像だが中身が壊れている）ファイルは、例外を投げず従来のチップ表示へフォールバックする。 */
    @Test
    fun addStagedCorruptImageFallsBackToNoThumbnail() = runTest {
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

        composer.addStaged(listOf(tempCorruptImageFile("h")))

        val staged = composer.ui().attachments!!.staged.value
        assertNull(staged.single().thumbnail)
    }
}
