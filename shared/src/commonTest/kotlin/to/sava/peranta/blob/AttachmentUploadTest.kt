package to.sava.peranta.blob

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import to.sava.peranta.model.AttachmentKind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttachmentUploadTest {

    private val sharedKey: ByteArray = ByteArray(32) { (it * 5 + 1).toByte() }

    private fun request(plain: ByteArray, fileName: String) = AttachmentUploadRequest(
        fileName = fileName,
        mimeType = "image/jpeg",
        sizeBytes = plain.size.toLong(),
        kind = AttachmentKind.IMAGE,
        openSource = { ByteReadChannel(plain) },
    )

    /**
     * アップロードは blobTopic へ暗号文長ちょうどを PUT し、返る [AttachmentRef] は
     * 暗号パラメータ・URL・種別・無害化済みファイル名を保持する。保存された暗号文は元の平文へ復号できる。
     */
    @Test
    fun uploadsEncryptedBlobAndBuildsRef() = runTest {
        val plain = Random(7).nextBytes(2 * DEFAULT_CHUNK_SIZE + 321)
        val transport = FakeBlobTransport()
        val cipher = BlobCipher(sharedKey, "k1")

        val ref = uploadAttachment(
            transport = transport,
            blobCipher = cipher,
            blobTopic = "peranta-blob-xyz",
            request = request(plain, "holiday.JPG"),
            newBlobId = { "blob-fixed" },
        )

        assertEquals("blob-fixed", ref.blobId)
        assertEquals("k1", ref.enc.keyId)
        assertEquals(plain.size.toLong(), ref.sizeBytes)
        assertEquals(AttachmentKind.IMAGE, ref.kind)
        assertEquals("holiday.JPG", ref.fileName)
        assertEquals("https://blob.invalid/file/blob-fixed", ref.url)

        val uploaded = transport.uploads.single()
        assertEquals("peranta-blob-xyz", uploaded.topic)
        assertEquals("blob-fixed", uploaded.blobId)
        val expectedCipherLen = cipherLenFor(plain.size.toLong(), ref.enc.totalChunks)
        assertEquals(expectedCipherLen, uploaded.contentLength)
        assertEquals(expectedCipherLen, uploaded.body.size.toLong())

        val roundTripped = drainToBytes { output ->
            BlobCipher(sharedKey, "k1").decrypt(
                "blob-fixed",
                ref.enc,
                ref.sizeBytes,
                ByteReadChannel(uploaded.body),
                output,
            )
        }
        assertContentEquals(plain, roundTripped)
    }

    /** 長大・パス付きのファイル名は無害化・長さ制限を掛けてから載せる。 */
    @Test
    fun sanitizesAndTruncatesFileName() = runTest {
        val plain = Random(8).nextBytes(64)
        val ref = uploadAttachment(
            transport = FakeBlobTransport(),
            blobCipher = BlobCipher(sharedKey, "k1"),
            blobTopic = "t",
            request = request(plain, "../../" + "あ".repeat(100) + ".png"),
            newBlobId = { "b" },
        )
        assertTrue(ref.fileName.encodeToByteArray().size <= MAX_ATTACHMENT_FILENAME_BYTES)
        assertTrue(ref.fileName.endsWith(".png"))
        assertTrue(!ref.fileName.contains('/'))
    }
}
