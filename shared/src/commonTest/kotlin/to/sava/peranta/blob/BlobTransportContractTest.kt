package to.sava.peranta.blob

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import to.sava.peranta.model.BlobEnc
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BlobTransportContractTest {

    private val sharedKey: ByteArray = ByteArray(32) { (it + 1).toByte() }

    /** アップロードした本体は同じ URL でダウンロードして一致する。 */
    @Test
    fun uploadThenDownloadRoundTrips() = runTest {
        val transport = FakeBlobTransport()
        val body = ByteArray(500) { it.toByte() }
        val uploaded = transport.upload("peranta-blob-x", "blob-1", body.size.toLong()) { channel ->
            channel.writeByteArray(body)
        }
        assertEquals(9_000, uploaded.blobExpiresAtEpochMillis)
        var downloaded = ByteArray(0)
        transport.download(uploaded.url, "blob-1") { downloaded = it.readRemaining().readByteArray() }
        assertContentEquals(body, downloaded)
    }

    /** アップロードは topic・blobId・contentLength・本体を記録する。 */
    @Test
    fun uploadRecordsMetadata() = runTest {
        val transport = FakeBlobTransport()
        val body = ByteArray(42)
        transport.upload("peranta-blob-y", "blob-2", body.size.toLong()) { it.writeByteArray(body) }
        val recorded = transport.uploads.single()
        assertEquals("peranta-blob-y", recorded.topic)
        assertEquals("blob-2", recorded.blobId)
        assertEquals(42, recorded.contentLength)
        assertContentEquals(body, recorded.body)
    }

    /** 未知の URL のダウンロードは失敗する（保存していない blob）。 */
    @Test
    fun downloadUnknownUrlFails() = runTest {
        val transport = FakeBlobTransport()
        assertFailsWith<IllegalStateException> {
            transport.download("https://blob.invalid/file/nope", "nope") { it.readRemaining().readByteArray() }
        }
    }

    /**
     * 暗号化 → アップロード → ダウンロード → 復号の縦一本が往復する。
     * 暗号文の全長は [cipherLenFor] で決まり Content-Length に使える。
     */
    @Test
    fun encryptUploadDownloadDecryptEndToEnd() = runTest {
        val plain = "添付の中身。バイナリでも同じ。".encodeToByteArray()
        val sender = BlobCipher(sharedKey, "k1")
        val transport = FakeBlobTransport()

        var enc: BlobEnc? = null
        val ciphertext = drainToBytes { output ->
            enc = sender.encrypt("blob-e2e", ByteReadChannel(plain), output, plain.size.toLong())
        }
        val blobEnc = enc!!
        assertEquals(cipherLenFor(plain.size.toLong(), blobEnc.totalChunks), ciphertext.size.toLong())

        val uploaded = transport.upload("peranta-blob-z", "blob-e2e", ciphertext.size.toLong()) { channel ->
            channel.writeByteArray(ciphertext)
        }

        val receiver = BlobCipher(sharedKey, "k1")
        var body = ByteArray(0)
        transport.download(uploaded.url, "blob-e2e") { body = it.readRemaining().readByteArray() }
        val roundTripped = drainToBytes { output ->
            receiver.decrypt("blob-e2e", blobEnc, plain.size.toLong(), ByteReadChannel(body), output)
        }
        assertContentEquals(plain, roundTripped)
    }
}
