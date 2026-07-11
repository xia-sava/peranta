package to.sava.peranta.blob

import to.sava.peranta.crypto.KeyIdMismatchException
import to.sava.peranta.model.BlobEnc
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BlobFormatTest {

    private fun salt16Base64(): String = Base64.encode(ByteArray(BLOB_SALT_SIZE) { it.toByte() })

    private fun enc(
        v: Int = 1,
        keyId: String = "k1",
        saltBase64: String = salt16Base64(),
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        totalChunks: Long,
    ) = BlobEnc(v = v, keyId = keyId, saltBase64 = saltBase64, chunkSize = chunkSize, totalChunks = totalChunks)

    /** HKDF info はバージョン付きの固定バイト列であること（テストベクタ）。 */
    @Test
    fun hkdfInfoIsFixedVector() {
        assertContentEquals("peranta:attachment:v=1".encodeToByteArray(), blobHkdfInfo())
    }

    /** nonce はチャンク番号を 88bit ビッグエンディアンで置き、最終バイトに最終フラグを立てる。 */
    @Test
    fun chunkNonceUsesBigEndianCounterAndFinalFlag() {
        // index 0, 非最終: 全 0。
        assertContentEquals(ByteArray(12), chunkNonce(0, isFinal = false))

        // index 0, 最終: 末尾のみ 0x01。
        val zeroFinal = ByteArray(12).also { it[11] = 0x01 }
        assertContentEquals(zeroFinal, chunkNonce(0, isFinal = true))

        // index 1, 非最終: nonce[10]=0x01。
        val one = ByteArray(12).also { it[10] = 0x01 }
        assertContentEquals(one, chunkNonce(1, isFinal = false))

        // index 258 (=0x0102), 最終: nonce[9]=0x01, nonce[10]=0x02, nonce[11]=0x01。
        val expected = ByteArray(12).also {
            it[9] = 0x01
            it[10] = 0x02
            it[11] = 0x01
        }
        assertContentEquals(expected, chunkNonce(258, isFinal = true))
    }

    /** AAD は blobId・チャンク番号・総チャンク数を束縛した固定文字列（テストベクタ）。 */
    @Test
    fun chunkAadIsFixedVector() {
        assertContentEquals(
            "peranta:blob:v=1:blobId=abc:chunk=2:total=5".encodeToByteArray(),
            chunkAad("abc", index = 2, totalChunks = 5),
        )
    }

    /** 総チャンク数は割り上げで、0 バイトは 1（空平文チャンク）。 */
    @Test
    fun totalChunksRoundsUpAndZeroIsOne() {
        assertEquals(1, totalChunksFor(0, DEFAULT_CHUNK_SIZE))
        assertEquals(1, totalChunksFor(1, DEFAULT_CHUNK_SIZE))
        assertEquals(1, totalChunksFor(DEFAULT_CHUNK_SIZE.toLong(), DEFAULT_CHUNK_SIZE))
        assertEquals(2, totalChunksFor(DEFAULT_CHUNK_SIZE + 1L, DEFAULT_CHUNK_SIZE))
        assertEquals(3, totalChunksFor(2L * DEFAULT_CHUNK_SIZE + 1, DEFAULT_CHUNK_SIZE))
    }

    /** 暗号文全長は平文 + チャンク毎のタグ 16B。 */
    @Test
    fun cipherLenAddsTagPerChunk() {
        assertEquals(16, cipherLenFor(0, 1))
        assertEquals(100 + 16, cipherLenFor(100, 1))
        assertEquals(DEFAULT_CHUNK_SIZE + 1L + 32, cipherLenFor(DEFAULT_CHUNK_SIZE + 1L, 2))
    }

    /** 平文チャンク長は中間=chunkSize、最終=残り。境界ちょうどでも正しい。 */
    @Test
    fun plainChunkLenSplitsMiddleAndFinal() {
        val size = 2L * DEFAULT_CHUNK_SIZE + 100
        val total = totalChunksFor(size, DEFAULT_CHUNK_SIZE)
        assertEquals(3, total)
        assertEquals(DEFAULT_CHUNK_SIZE.toLong(), plainChunkLen(0, total, size, DEFAULT_CHUNK_SIZE))
        assertEquals(DEFAULT_CHUNK_SIZE.toLong(), plainChunkLen(1, total, size, DEFAULT_CHUNK_SIZE))
        assertEquals(100, plainChunkLen(2, total, size, DEFAULT_CHUNK_SIZE))

        // 境界ちょうど（1 チャンク分ぴったり）は最終チャンクが chunkSize。
        val exact = DEFAULT_CHUNK_SIZE.toLong()
        assertEquals(exact, plainChunkLen(0, 1, exact, DEFAULT_CHUNK_SIZE))
    }

    /** 空ファイルは 1 チャンク・平文 0・暗号文 16。 */
    @Test
    fun emptyBlobHasSingleTagOnlyChunk() {
        assertEquals(1, totalChunksFor(0, DEFAULT_CHUNK_SIZE))
        assertEquals(0, plainChunkLen(0, 1, 0, DEFAULT_CHUNK_SIZE))
        assertEquals(16, cipherChunkLen(0, 1, 0, DEFAULT_CHUNK_SIZE))
    }

    /** 正当な BlobEnc は検証を通り、salt を復号したレイアウトを返す。 */
    @Test
    fun validateAcceptsWellFormedEnc() {
        val size = 2L * DEFAULT_CHUNK_SIZE + 100
        val layout = validateBlobEnc(enc(totalChunks = 3), expectedKeyId = "k1", sizeBytes = size)
        assertEquals(DEFAULT_CHUNK_SIZE, layout.chunkSize)
        assertEquals(3, layout.totalChunks)
        assertEquals(size, layout.sizeBytes)
        assertEquals(BLOB_SALT_SIZE, layout.salt.size)
    }

    /** 未対応バージョンは BlobFormatException。 */
    @Test
    fun validateRejectsUnsupportedVersion() {
        assertFailsWith<BlobFormatException> {
            validateBlobEnc(enc(v = 2, totalChunks = 1), "k1", 0)
        }
    }

    /** keyId 不一致は KeyIdMismatchException（再ペアリング誘導）。 */
    @Test
    fun validateRejectsKeyIdMismatch() {
        val error = assertFailsWith<KeyIdMismatchException> {
            validateBlobEnc(enc(keyId = "k9", totalChunks = 1), "k1", 0)
        }
        assertEquals("k1", error.expected)
        assertEquals("k9", error.actual)
    }

    /** 範囲外の chunkSize（小さすぎ・大きすぎ）は BlobFormatException。 */
    @Test
    fun validateRejectsOutOfRangeChunkSize() {
        assertFailsWith<BlobFormatException> {
            validateBlobEnc(enc(chunkSize = MIN_CHUNK_SIZE - 1, totalChunks = 1), "k1", 100)
        }
        assertFailsWith<BlobFormatException> {
            validateBlobEnc(enc(chunkSize = MAX_CHUNK_SIZE + 1, totalChunks = 1), "k1", 100)
        }
    }

    /** chunkSize の境界値（最小・最大）は受理される。 */
    @Test
    fun validateAcceptsChunkSizeBoundaries() {
        validateBlobEnc(enc(chunkSize = MIN_CHUNK_SIZE, totalChunks = 1), "k1", 100)
        validateBlobEnc(enc(chunkSize = MAX_CHUNK_SIZE, totalChunks = 1), "k1", 100)
    }

    /** sizeBytes が上限超過は BlobFormatException。 */
    @Test
    fun validateRejectsSizeOverLimit() {
        val over = MAX_BLOB_SIZE_BYTES + 1
        assertFailsWith<BlobFormatException> {
            validateBlobEnc(enc(totalChunks = totalChunksFor(over, DEFAULT_CHUNK_SIZE)), "k1", over)
        }
    }

    /** totalChunks が sizeBytes/chunkSize と不整合なら BlobFormatException。 */
    @Test
    fun validateRejectsTotalChunksMismatch() {
        assertFailsWith<BlobFormatException> {
            validateBlobEnc(enc(totalChunks = 2), "k1", 100)
        }
    }

    /** salt が 16 バイトでない・base64 でないと BlobFormatException。 */
    @Test
    fun validateRejectsBadSalt() {
        assertFailsWith<BlobFormatException> {
            validateBlobEnc(enc(saltBase64 = Base64.encode(ByteArray(8)), totalChunks = 1), "k1", 0)
        }
        assertFailsWith<BlobFormatException> {
            validateBlobEnc(enc(saltBase64 = "!!not-base64!!", totalChunks = 1), "k1", 0)
        }
    }

    /** チャンクサイズ範囲は仕様どおり 64KiB〜8MiB、既定は 1MiB。 */
    @Test
    fun constantsMatchSpec() {
        assertEquals(1 shl 20, DEFAULT_CHUNK_SIZE)
        assertEquals(1 shl 16, MIN_CHUNK_SIZE)
        assertEquals(1 shl 23, MAX_CHUNK_SIZE)
        assertEquals(1L shl 30, MAX_BLOB_SIZE_BYTES)
        assertTrue(DEFAULT_CHUNK_SIZE in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE)
    }
}
