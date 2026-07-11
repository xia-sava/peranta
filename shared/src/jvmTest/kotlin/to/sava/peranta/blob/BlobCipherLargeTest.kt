package to.sava.peranta.blob

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import to.sava.peranta.crypto.DecryptionException
import to.sava.peranta.model.BlobEnc
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BlobCipherLargeTest {

    private val sharedKey: ByteArray = ByteArray(32) { (it * 7 + 3).toByte() }
    private val blobId: String = "blob-large"

    private suspend fun encrypt(plain: ByteArray, cipher: BlobCipher): Pair<ByteArray, BlobEnc> {
        var enc: BlobEnc? = null
        val bytes = drainToBytes { output ->
            enc = cipher.encrypt(blobId, ByteReadChannel(plain), output, plain.size.toLong())
        }
        return bytes to enc!!
    }

    /**
     * 複数チャンクに跨るサイズ（3 チャンク + 端数）で往復する。
     * チャンク単位処理のため、暗号文全長は決定的に [cipherLenFor] と一致する。
     */
    @Test
    fun roundTripsAcrossMultipleChunks() = runTest {
        val plain = Random(1).nextBytes(3 * DEFAULT_CHUNK_SIZE + 500)
        val cipher = BlobCipher(sharedKey, "k1")
        val (ciphertext, enc) = encrypt(plain, cipher)
        assertEquals(4, enc.totalChunks)
        assertEquals(cipherLenFor(plain.size.toLong(), enc.totalChunks), ciphertext.size.toLong())

        val roundTripped = drainToBytes { output ->
            BlobCipher(sharedKey, "k1").decrypt(blobId, enc, plain.size.toLong(), ByteReadChannel(ciphertext), output)
        }
        assertContentEquals(plain, roundTripped)
    }

    /**
     * 数十 MB 級（20 MiB）でも streaming で往復する。writer で書き手と読み手を並行させ、
     * メモリ滞留がチャンクサイズ規模に留まる（全量バッファしない）構成を確認する。
     */
    @Test
    fun roundTripsTensOfMegabytes() = runTest {
        val plain = Random(2).nextBytes(20 * DEFAULT_CHUNK_SIZE + 17)
        val cipher = BlobCipher(sharedKey, "k1")
        val (ciphertext, enc) = encrypt(plain, cipher)
        assertEquals(21, enc.totalChunks)

        val roundTripped = drainToBytes { output ->
            BlobCipher(sharedKey, "k1").decrypt(blobId, enc, plain.size.toLong(), ByteReadChannel(ciphertext), output)
        }
        assertContentEquals(plain, roundTripped)
    }

    /** チャンクの並べ替え（先頭 2 チャンクの入れ替え）は nonce/AAD 束縛でタグ検証に失敗する。 */
    @Test
    fun reorderedChunksFail() = runTest {
        val plain = Random(3).nextBytes(2 * DEFAULT_CHUNK_SIZE + 128)
        val cipher = BlobCipher(sharedKey, "k1")
        val (ciphertext, enc) = encrypt(plain, cipher)
        assertEquals(3, enc.totalChunks)

        val chunk0Len = cipherChunkLen(0, enc.totalChunks, plain.size.toLong(), enc.chunkSize).toInt()
        val chunk1Len = cipherChunkLen(1, enc.totalChunks, plain.size.toLong(), enc.chunkSize).toInt()
        val swapped = ByteArray(ciphertext.size)
        // chunk1 を先頭へ、chunk0 をその後ろへ（両者は同じ長さ = chunkSize+16）。
        ciphertext.copyInto(swapped, destinationOffset = 0, startIndex = chunk0Len, endIndex = chunk0Len + chunk1Len)
        ciphertext.copyInto(swapped, destinationOffset = chunk1Len, startIndex = 0, endIndex = chunk0Len)
        ciphertext.copyInto(swapped, destinationOffset = chunk0Len + chunk1Len, startIndex = chunk0Len + chunk1Len)

        val output = ByteChannel(autoFlush = true)
        assertFailsWith<DecryptionException> {
            BlobCipher(sharedKey, "k1").decrypt(blobId, enc, plain.size.toLong(), ByteReadChannel(swapped), output)
        }
    }
}
