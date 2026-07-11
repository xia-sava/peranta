package to.sava.peranta.blob

import to.sava.peranta.crypto.KeyIdMismatchException
import to.sava.peranta.model.BLOB_FORMAT_VERSION
import to.sava.peranta.model.BlobEnc
import kotlin.io.encoding.Base64

/** v1 生成時の固定チャンクサイズ（1 MiB）。 */
const val DEFAULT_CHUNK_SIZE: Int = 1 shl 20

/** 復号側が許容する最小チャンクサイズ（64 KiB）。範囲外は細工値として拒否する。 */
const val MIN_CHUNK_SIZE: Int = 1 shl 16

/** 復号側が許容する最大チャンクサイズ（8 MiB）。範囲外は OOM 防止のため拒否する。 */
const val MAX_CHUNK_SIZE: Int = 1 shl 23

/** 対応する blob 平文サイズの上限（1 GiB、300MB 目標に対する安全マージン）。 */
const val MAX_BLOB_SIZE_BYTES: Long = 1L shl 30

/** HKDF salt のバイト長。 */
const val BLOB_SALT_SIZE: Int = 16

/** 共有鍵から導出する blobKey のバイト長。 */
const val BLOB_KEY_SIZE: Int = 32

/** AES-GCM の nonce 長（バイト）。 */
const val BLOB_NONCE_SIZE: Int = 12

/** AES-GCM の認証タグ長（バイト）。各チャンク暗号文の末尾に付く。 */
const val BLOB_GCM_TAG_SIZE: Int = 16

/** nonce のうちチャンク番号を格納するビッグエンディアン領域のバイト長（88bit）。 */
private const val NONCE_COUNTER_SIZE: Int = 11

/** nonce 最終バイトに入れる最終チャンクフラグ。 */
private const val FINAL_CHUNK_FLAG: Byte = 0x01

/** nonce 最終バイトに入れる非最終チャンクフラグ。 */
private const val NON_FINAL_CHUNK_FLAG: Byte = 0x00

/** blob フォーマットの構造検証に失敗したことを示す（細工値・切り詰め・伸長・不整合）。 */
class BlobFormatException(message: String) : Exception(message)

/**
 * [validateBlobEnc] を通した blob レイアウト。チャンク読み出しの長さ計算に使う。
 * [salt] は base64 から復号済みで、長さ検証も済んでいる。
 */
class BlobLayout(
    val chunkSize: Int,
    val totalChunks: Long,
    val sizeBytes: Long,
    val salt: ByteArray,
)

/** blob 毎の HKDF info（RFC 5869、固定文字列）を返す。 */
fun blobHkdfInfo(): ByteArray = "peranta:attachment:v=$BLOB_FORMAT_VERSION".encodeToByteArray()

/**
 * チャンク [index]（0 始まり）と最終チャンクか [isFinal] から 12 バイト nonce を決定的に組む。
 * nonce[0..10] にチャンク番号を 88bit ビッグエンディアンで格納し、nonce[11] に最終フラグを置く。
 * ビッグエンディアン固定で Android/JVM 間の差異を排除する。
 */
fun chunkNonce(index: Long, isFinal: Boolean): ByteArray {
    require(index >= 0) { "chunk index must be non-negative, was $index" }
    val nonce = ByteArray(BLOB_NONCE_SIZE)
    var remaining = index
    for (offset in NONCE_COUNTER_SIZE - 1 downTo 0) {
        nonce[offset] = (remaining and 0xFF).toByte()
        remaining = remaining ushr 8
    }
    nonce[NONCE_COUNTER_SIZE] = if (isFinal) FINAL_CHUNK_FLAG else NON_FINAL_CHUNK_FLAG
    return nonce
}

/**
 * チャンク [index]（0 始まり）の AAD を組む（§4.3、[MessageCipher] と同じ文字列連結の作法）。
 * blobId・チャンク番号・総チャンク数を束縛し、改竄・並べ替え・切り詰め・伸長を検出する。
 */
fun chunkAad(blobId: String, index: Long, totalChunks: Long): ByteArray =
    "peranta:blob:v=$BLOB_FORMAT_VERSION:blobId=$blobId:chunk=$index:total=$totalChunks".encodeToByteArray()

/** [sizeBytes] を [chunkSize] で割り上げた総チャンク数。0 バイトは 1（空平文チャンク）。 */
fun totalChunksFor(sizeBytes: Long, chunkSize: Int): Long {
    require(sizeBytes >= 0) { "sizeBytes must be non-negative, was $sizeBytes" }
    require(chunkSize > 0) { "chunkSize must be positive, was $chunkSize" }
    if (sizeBytes == 0L) return 1
    return (sizeBytes + chunkSize - 1) / chunkSize
}

/** [totalChunks] 個のチャンクを持つ blob の暗号文全長（各チャンクにタグ 16B が付く）。 */
fun cipherLenFor(sizeBytes: Long, totalChunks: Long): Long =
    sizeBytes + BLOB_GCM_TAG_SIZE.toLong() * totalChunks

/** チャンク [index] の平文長。中間チャンクは [chunkSize]、最終チャンクは残り。 */
fun plainChunkLen(index: Long, totalChunks: Long, sizeBytes: Long, chunkSize: Int): Long {
    require(index in 0 until totalChunks) { "index $index out of range 0..<$totalChunks" }
    val isFinal = index == totalChunks - 1
    return if (isFinal) sizeBytes - (totalChunks - 1) * chunkSize else chunkSize.toLong()
}

/** チャンク [index] の暗号文長（平文長 + タグ 16B）。 */
fun cipherChunkLen(index: Long, totalChunks: Long, sizeBytes: Long, chunkSize: Int): Long =
    plainChunkLen(index, totalChunks, sizeBytes, chunkSize) + BLOB_GCM_TAG_SIZE

/**
 * 復号前に [enc] を [expectedKeyId] と [sizeBytes] に対して検証する（§4.3 復号側の事前検証 1）。
 * バージョン・chunkSize 範囲・sizeBytes 上限・totalChunks 整合・salt 長を全て満たさなければ例外を投げる。
 * keyId 不一致は再ペアリング誘導のため [KeyIdMismatchException]、他の構造不正は [BlobFormatException]。
 */
fun validateBlobEnc(enc: BlobEnc, expectedKeyId: String, sizeBytes: Long): BlobLayout {
    if (enc.v != BLOB_FORMAT_VERSION) {
        throw BlobFormatException("unsupported blob format version: ${enc.v}")
    }
    if (enc.keyId != expectedKeyId) {
        throw KeyIdMismatchException(expected = expectedKeyId, actual = enc.keyId)
    }
    if (enc.chunkSize !in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE) {
        throw BlobFormatException("chunkSize ${enc.chunkSize} out of range $MIN_CHUNK_SIZE..$MAX_CHUNK_SIZE")
    }
    if (sizeBytes < 0 || sizeBytes > MAX_BLOB_SIZE_BYTES) {
        throw BlobFormatException("sizeBytes $sizeBytes out of range 0..$MAX_BLOB_SIZE_BYTES")
    }
    val expectedTotal = totalChunksFor(sizeBytes, enc.chunkSize)
    if (enc.totalChunks != expectedTotal) {
        throw BlobFormatException("totalChunks ${enc.totalChunks} does not match expected $expectedTotal")
    }
    val salt = decodeSalt(enc.saltBase64)
    if (salt.size != BLOB_SALT_SIZE) {
        throw BlobFormatException("salt length ${salt.size} is not $BLOB_SALT_SIZE")
    }
    return BlobLayout(enc.chunkSize, enc.totalChunks, sizeBytes, salt)
}

private fun decodeSalt(saltBase64: String): ByteArray =
    try {
        Base64.decode(saltBase64)
    } catch (error: IllegalArgumentException) {
        throw BlobFormatException("salt is not valid base64")
    }
