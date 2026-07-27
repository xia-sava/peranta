package to.sava.peranta.blob

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import to.sava.peranta.crypto.DecryptionException
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopAttachmentCacheTest {

    private val sharedKey: ByteArray = ByteArray(32) { (it * 3 + 2).toByte() }
    private val baseDir: File = Files.createTempDirectory("peranta-attach-test").toFile()

    @AfterTest
    fun cleanup() {
        baseDir.deleteRecursively()
    }

    private suspend fun uploadPlain(
        transport: FakeBlobTransport,
        plain: ByteArray,
        fileName: String = "photo.jpg",
        blobId: String = "blob-1",
    ): AttachmentRef = uploadAttachment(
        transport = transport,
        blobCipher = BlobCipher(sharedKey, "k1"),
        blobTopic = "t",
        request = AttachmentUploadRequest(
            fileName = fileName,
            mimeType = "image/jpeg",
            sizeBytes = plain.size.toLong(),
            kind = AttachmentKind.IMAGE,
            openSource = { ByteReadChannel(plain) },
        ),
        newBlobId = { blobId },
    )

    private fun cache(transport: BlobTransport, now: () -> Long = { 1_000_000L }) =
        DesktopAttachmentCache(transport, sharedKey, "k1", baseDir = baseDir, now = now)

    /** ダウンロードは blob を復号してキャッシュへ保存し、内容が元の平文と一致する。 */
    @Test
    fun downloadDecryptsToCache() = runTest {
        val plain = Random(11).nextBytes(2 * DEFAULT_CHUNK_SIZE + 77)
        val transport = FakeBlobTransport()
        val ref = uploadPlain(transport, plain)

        var lastProgress = 0L
        val file = cache(transport).download(ref) { lastProgress = it }

        assertTrue(file.exists())
        assertContentEquals(plain, file.readBytes())
        assertEquals(plain.size.toLong(), lastProgress)
        assertEquals(baseDir.resolve("blob-1").resolve("photo.jpg"), file)
    }

    /** 一度ダウンロードした添付は再ダウンロードせずキャッシュから返る。 */
    @Test
    fun cachedFileReturnedWithoutRedownload() = runTest {
        val plain = Random(12).nextBytes(1024)
        val transport = FakeBlobTransport()
        val ref = uploadPlain(transport, plain)
        val cache = cache(transport)
        cache.download(ref)
        assertEquals(1, transport.uploads.size)
        val cached = cache.cachedFile(ref)
        assertTrue(cached != null && cached.exists())
    }

    /** 改竄された暗号文は復号に失敗し、部分ファイル（.part）を残さない。 */
    @Test
    fun tamperedBlobLeavesNoPartialFile() = runTest {
        val plain = Random(13).nextBytes(4096)
        val source = FakeBlobTransport()
        val ref = uploadPlain(source, plain)
        val ciphertext = source.uploads.single().body.copyOf()
        ciphertext[ciphertext.size / 2] = (ciphertext[ciphertext.size / 2].toInt() xor 0x7F).toByte()
        val transport = SingleBlobTransport(ref.url, ciphertext)

        assertFailsWith<DecryptionException> {
            cache(transport).download(ref)
        }
        val blobDir = baseDir.resolve("blob-1")
        val leftovers = blobDir.listFiles()?.toList().orEmpty()
        assertTrue(leftovers.isEmpty(), "unexpected leftovers: ${leftovers.map { it.name }}")
        assertNull(cache(transport).cachedFile(ref))
    }

    /** パストラバーサルを狙うファイル名・blobId でもキャッシュ基点ディレクトリの外へ書かない。 */
    @Test
    fun pathTraversalIsContained() = runTest {
        val plain = Random(14).nextBytes(512)
        val transport = FakeBlobTransport()
        val ref = uploadPlain(transport, plain, fileName = "../../evil.jpg", blobId = "../escape")
        val file = cache(transport).download(ref)
        assertTrue(file.canonicalPath.startsWith(baseDir.canonicalPath), "escaped base dir: ${file.canonicalPath}")
        assertTrue(file.name == "evil.jpg")
    }

    /** 剪定は保持上限を超えたキャッシュディレクトリを削除する。 */
    @Test
    fun prunesExpiredEntries() {
        val stale = File(baseDir, "stale").apply { mkdirs() }
        File(stale, "a.bin").writeBytes(ByteArray(10))
        stale.listFiles()!!.forEach { it.setLastModified(0L) }
        stale.setLastModified(0L)

        val cache = cache(FakeBlobTransport(), now = { ATTACHMENT_CACHE_MAX_AGE_MILLIS + 1_000_000L })
        cache.prune()
        assertTrue(!stale.exists())
    }

    /** 指定 URL に対して固定バイト列を返すだけの [BlobTransport]。 */
    private class SingleBlobTransport(
        private val url: String,
        private val body: ByteArray,
    ) : BlobTransport {
        override suspend fun upload(
            topic: String,
            blobId: String,
            contentLength: Long,
            writeBody: suspend (io.ktor.utils.io.ByteWriteChannel) -> Unit,
        ): UploadedBlob = error("not used")

        override suspend fun download(url: String, blobId: String, consume: suspend (ByteReadChannel) -> Unit) {
            check(url == this.url) { "unexpected url $url" }
            consume(ByteReadChannel(body))
        }
    }
}
