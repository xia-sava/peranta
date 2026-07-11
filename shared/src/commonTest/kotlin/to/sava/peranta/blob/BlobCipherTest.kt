package to.sava.peranta.blob

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import to.sava.peranta.crypto.DecryptionException
import to.sava.peranta.crypto.KeyIdMismatchException
import to.sava.peranta.model.BlobEnc
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BlobCipherTest {

    private val sharedKey: ByteArray = ByteArray(32) { (it + 1).toByte() }
    private val blobId: String = "blob-abc"

    private fun cipher(key: ByteArray = sharedKey, keyId: String = "k1") = BlobCipher(key, keyId)

    private suspend fun encryptToBytes(plain: ByteArray, cipher: BlobCipher = cipher()): Pair<ByteArray, BlobEnc> {
        var enc: BlobEnc? = null
        val bytes = drainToBytes { output ->
            enc = cipher.encrypt(blobId, ByteReadChannel(plain), output, plain.size.toLong())
        }
        return bytes to enc!!
    }

    private suspend fun decryptToBytes(
        enc: BlobEnc,
        sizeBytes: Long,
        ciphertext: ByteArray,
        cipher: BlobCipher = cipher(),
    ): ByteArray = drainToBytes { output ->
        cipher.decrypt(blobId, enc, sizeBytes, ByteReadChannel(ciphertext), output)
    }

    /** 出力を捨てつつ復号を実行する。失敗系テストで例外を呼び出し側の assertFailsWith へ通す。 */
    private suspend fun decryptDiscardingOutput(
        enc: BlobEnc,
        sizeBytes: Long,
        ciphertext: ByteArray,
        cipher: BlobCipher = cipher(),
        blobId: String = this.blobId,
    ) {
        val output = ByteChannel(autoFlush = true)
        cipher.decrypt(blobId, enc, sizeBytes, ByteReadChannel(ciphertext), output)
    }

    /** 小さいデータは封緘・開封を往復する。 */
    @Test
    fun roundTripsSmallData() = runTest {
        val plain = "こんにちは、Peranta blob。".encodeToByteArray()
        val (ciphertext, enc) = encryptToBytes(plain)
        assertContentEquals(plain, decryptToBytes(enc, plain.size.toLong(), ciphertext))
    }

    /** 空ファイルはタグのみ 16 バイトの暗号文になり、開封で空平文へ戻る。 */
    @Test
    fun roundTripsEmptyBlob() = runTest {
        val (ciphertext, enc) = encryptToBytes(ByteArray(0))
        assertEquals(16, ciphertext.size)
        assertEquals(1, enc.totalChunks)
        assertContentEquals(ByteArray(0), decryptToBytes(enc, 0, ciphertext))
    }

    /** 暗号文全長は sizeBytes と totalChunks から決定的に計算できる（Content-Length 用）。 */
    @Test
    fun ciphertextLengthIsDeterministic() = runTest {
        val plain = ByteArray(1234) { it.toByte() }
        val (ciphertext, enc) = encryptToBytes(plain)
        assertEquals(cipherLenFor(plain.size.toLong(), enc.totalChunks), ciphertext.size.toLong())
        assertEquals(plain.size.toLong() + 16, ciphertext.size.toLong())
    }

    /** enc には新規 salt が入り、毎回異なる（決定的でない）。 */
    @Test
    fun encryptGeneratesFreshSalt() = runTest {
        val plain = ByteArray(64)
        val (_, enc1) = encryptToBytes(plain)
        val (_, enc2) = encryptToBytes(plain)
        assertEquals("k1", enc1.keyId)
        assertEquals(1, enc1.totalChunks)
        kotlin.test.assertNotEquals(enc1.saltBase64, enc2.saltBase64)
    }

    /** 暗号文の 1 バイト改竄はタグ検証失敗（DecryptionException）で即中断する。 */
    @Test
    fun tamperedCiphertextFails() = runTest {
        val plain = ByteArray(200) { it.toByte() }
        val (ciphertext, enc) = encryptToBytes(plain)
        val tampered = ciphertext.copyOf().also { it[10] = (it[10].toInt() xor 0x01).toByte() }
        assertFailsWith<DecryptionException> {
            decryptDiscardingOutput(enc, plain.size.toLong(), tampered)
        }
    }

    /** 末尾のタグ改竄も DecryptionException。 */
    @Test
    fun tamperedTagFails() = runTest {
        val plain = ByteArray(200) { it.toByte() }
        val (ciphertext, enc) = encryptToBytes(plain)
        val tampered = ciphertext.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        assertFailsWith<DecryptionException> {
            decryptDiscardingOutput(enc, plain.size.toLong(), tampered)
        }
    }

    /** 暗号文を切り詰めると BlobFormatException（切り詰め攻撃）。 */
    @Test
    fun truncatedCiphertextFails() = runTest {
        val plain = ByteArray(200) { it.toByte() }
        val (ciphertext, enc) = encryptToBytes(plain)
        val truncated = ciphertext.copyOf(ciphertext.size - 1)
        assertFailsWith<BlobFormatException> {
            decryptDiscardingOutput(enc, plain.size.toLong(), truncated)
        }
    }

    /** 暗号文の末尾に余分なバイトがあると BlobFormatException（伸長攻撃）。 */
    @Test
    fun extendedCiphertextFails() = runTest {
        val plain = ByteArray(200) { it.toByte() }
        val (ciphertext, enc) = encryptToBytes(plain)
        val extended = ciphertext + byteArrayOf(0x00)
        assertFailsWith<BlobFormatException> {
            decryptDiscardingOutput(enc, plain.size.toLong(), extended)
        }
    }

    /** 共有鍵が違えば導出鍵が異なり、タグ検証に失敗する（DecryptionException）。 */
    @Test
    fun wrongSharedKeyFails() = runTest {
        val plain = ByteArray(128) { it.toByte() }
        val (ciphertext, enc) = encryptToBytes(plain)
        val otherKey = ByteArray(32) { (it + 99).toByte() }
        assertFailsWith<DecryptionException> {
            decryptDiscardingOutput(enc, plain.size.toLong(), ciphertext, cipher(key = otherKey))
        }
    }

    /** 復号時の blobId が暗号化時と異なると AAD 不一致でタグ検証に失敗する（DecryptionException）。 */
    @Test
    fun mismatchedBlobIdFails() = runTest {
        val plain = ByteArray(128) { it.toByte() }
        val (ciphertext, enc) = encryptToBytes(plain)
        assertFailsWith<DecryptionException> {
            decryptDiscardingOutput(enc, plain.size.toLong(), ciphertext, blobId = "different-blob-id")
        }
    }

    /** enc の keyId が自鍵と一致しないと KeyIdMismatchException。 */
    @Test
    fun keyIdMismatchFails() = runTest {
        val plain = ByteArray(128) { it.toByte() }
        val (ciphertext, enc) = encryptToBytes(plain)
        assertFailsWith<KeyIdMismatchException> {
            decryptDiscardingOutput(enc, plain.size.toLong(), ciphertext, cipher(keyId = "k2"))
        }
    }

    /** 宣言 sizeBytes が実データと食い違うと事前検証（totalChunks 不整合）で弾く。 */
    @Test
    fun mismatchedDeclaredSizeFails() = runTest {
        val plain = ByteArray(128) { it.toByte() }
        val (ciphertext, enc) = encryptToBytes(plain)
        // enc.totalChunks は 1（128B）。sizeBytes に 1MiB 超を宣言すると totalChunks 期待値が変わり不整合。
        assertFailsWith<BlobFormatException> {
            decryptDiscardingOutput(enc, DEFAULT_CHUNK_SIZE + 1L, ciphertext)
        }
    }

    /** 32 バイトでない共有鍵は生成時に弾く。 */
    @Test
    fun rejectsWrongSharedKeyLength() {
        assertFailsWith<IllegalArgumentException> { BlobCipher(ByteArray(16), "k1") }
    }
}
