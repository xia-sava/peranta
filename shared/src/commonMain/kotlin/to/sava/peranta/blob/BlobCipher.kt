package to.sava.peranta.blob

import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.exhausted
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.io.EOFException
import to.sava.peranta.crypto.DecryptionException
import to.sava.peranta.model.BLOB_FORMAT_VERSION
import to.sava.peranta.model.BlobEnc
import kotlin.io.encoding.Base64

/**
 * Peranta Blob Format v1 の暗号化・復号（§4.3）。[to.sava.peranta.crypto.MessageCipher] と対になる。
 * 共有鍵と blob 毎の salt から HKDF-SHA256 で blobKey を導出し、固定チャンクごとに AES-GCM 256bit を適用する。
 * チャンク単位で処理するためメモリ使用量は入力サイズではなくチャンクサイズに比例する
 * （ライブラリのストリーミング API が復号側で全量バッファするのを避けるための自前実装）。
 *
 * 復号は失敗時に例外を投げ、部分出力を完成させない。出力ファイルの原子性（tmp へ書いて成功後に rename）は
 * ファイル層の責務とし、本クラスは任意の失敗で途中まで書いた [ByteWriteChannel] を完成扱いにしないことだけを保証する。
 */
class BlobCipher(sharedKey: ByteArray, val keyId: String) {

    init {
        require(sharedKey.size == BLOB_KEY_SIZE) { "shared key must be $BLOB_KEY_SIZE bytes, was ${sharedKey.size}" }
    }

    private val sharedKey: ByteArray = sharedKey.copyOf()

    /**
     * [input] から [sizeBytes] バイトを読み、チャンク暗号化して [output] へ連結出力する。
     * salt を新規生成し、復号に必要なパラメータを [BlobEnc] として返す。
     */
    @OptIn(DelicateCryptographyApi::class)
    suspend fun encrypt(
        blobId: String,
        input: ByteReadChannel,
        output: ByteWriteChannel,
        sizeBytes: Long,
    ): BlobEnc {
        require(sizeBytes in 0..MAX_BLOB_SIZE_BYTES) {
            "sizeBytes $sizeBytes out of range 0..$MAX_BLOB_SIZE_BYTES"
        }
        val salt = CryptographyRandom.Default.nextBytes(BLOB_SALT_SIZE)
        val cipher = blobCipher(salt)
        val chunkSize = DEFAULT_CHUNK_SIZE
        val totalChunks = totalChunksFor(sizeBytes, chunkSize)
        for (index in 0 until totalChunks) {
            val isFinal = index == totalChunks - 1
            val plainLen = plainChunkLen(index, totalChunks, sizeBytes, chunkSize).toInt()
            val plaintext = input.readByteArray(plainLen)
            val ciphertext = cipher.encryptWithIv(
                chunkNonce(index, isFinal),
                plaintext,
                chunkAad(blobId, index, totalChunks),
            )
            output.writeByteArray(ciphertext)
        }
        output.flush()
        return BlobEnc(
            v = BLOB_FORMAT_VERSION,
            keyId = keyId,
            saltBase64 = Base64.encode(salt),
            chunkSize = chunkSize,
            totalChunks = totalChunks,
        )
    }

    /**
     * [enc] を事前検証し、[input] のチャンク暗号文を順に復号して [output] へ平文を連結出力する。
     * タグ検証失敗は [DecryptionException]、切り詰め・伸長・サイズ不整合は [BlobFormatException]、
     * keyId 不一致は [to.sava.peranta.crypto.KeyIdMismatchException] を投げる（いずれも握り潰さない）。
     */
    @OptIn(DelicateCryptographyApi::class)
    suspend fun decrypt(
        blobId: String,
        enc: BlobEnc,
        sizeBytes: Long,
        input: ByteReadChannel,
        output: ByteWriteChannel,
    ) {
        val layout = validateBlobEnc(enc, keyId, sizeBytes)
        val cipher = blobCipher(layout.salt)
        var writtenPlain = 0L
        for (index in 0 until layout.totalChunks) {
            val isFinal = index == layout.totalChunks - 1
            val cipherLen = cipherChunkLen(index, layout.totalChunks, sizeBytes, layout.chunkSize).toInt()
            val ciphertext = readChunkOrThrow(input, cipherLen, index)
            val plaintext = try {
                cipher.decryptWithIv(
                    chunkNonce(index, isFinal),
                    ciphertext,
                    chunkAad(blobId, index, layout.totalChunks),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                throw DecryptionException("failed to decrypt blob chunk $index", error)
            }
            output.writeByteArray(plaintext)
            writtenPlain += plaintext.size
        }
        if (!input.exhausted()) {
            throw BlobFormatException("extra data after final blob chunk (length extension)")
        }
        if (writtenPlain != sizeBytes) {
            throw BlobFormatException("decrypted size $writtenPlain does not match declared $sizeBytes")
        }
        output.flush()
    }

    private suspend fun readChunkOrThrow(input: ByteReadChannel, cipherLen: Int, index: Long): ByteArray =
        try {
            input.readByteArray(cipherLen)
        } catch (error: EOFException) {
            throw BlobFormatException("blob stream truncated at chunk $index")
        }

    private suspend fun blobCipher(salt: ByteArray): AES.IvAuthenticatedCipher {
        val blobKey = CryptographyProvider.Default
            .get(HKDF)
            .secretDerivation(
                digest = SHA256,
                outputSize = BLOB_KEY_SIZE.bytes,
                salt = salt,
                info = blobHkdfInfo(),
            )
            .deriveSecretToByteArray(sharedKey)
        return CryptographyProvider.Default
            .get(AES.GCM)
            .keyDecoder()
            .decodeFromByteArrayBlocking(AES.Key.Format.RAW, blobKey)
            .cipher()
    }
}
