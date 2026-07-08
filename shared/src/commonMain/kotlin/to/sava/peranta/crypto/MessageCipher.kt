package to.sava.peranta.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.coroutines.CancellationException
import to.sava.peranta.model.ENVELOPE_VERSION
import to.sava.peranta.model.Envelope
import to.sava.peranta.model.Payload
import to.sava.peranta.model.decodePayload
import to.sava.peranta.model.encodePayload
import kotlin.io.encoding.Base64

/** AES-GCM の nonce 長（バイト）。 */
private const val NONCE_SIZE = 12

/** AES 鍵長（バイト）。 */
private const val KEY_SIZE = 32

/** keyId が受信側の鍵と一致しない場合に投げる。再ペアリングを促すため復号失敗と区別する。 */
class KeyIdMismatchException(val expected: String, val actual: String) :
    Exception("keyId mismatch: expected=$expected actual=$actual")

/** 復号・認証タグ検証に失敗した場合に投げる。改竄や鍵違いを示す。 */
class DecryptionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 32 バイトの暗号学的乱数鍵を生成する。 */
fun generateKey(): ByteArray = CryptographyRandom.Default.nextBytes(KEY_SIZE)

/**
 * Payload を AES-GCM 256bit で封緘・開封する。
 * AAD として v と keyId を束縛し、封筒メタデータの改竄をタグ検証に縛る。
 */
class MessageCipher(key: ByteArray, val keyId: String) {

    init {
        require(key.size == KEY_SIZE) { "key must be $KEY_SIZE bytes, was ${key.size}" }
    }

    private val cipher: AES.IvAuthenticatedCipher = CryptographyProvider.Default
        .get(AES.GCM)
        .keyDecoder()
        .decodeFromByteArrayBlocking(AES.Key.Format.RAW, key.copyOf())
        .cipher()

    @OptIn(DelicateCryptographyApi::class)
    suspend fun seal(payload: Payload): Envelope {
        val nonce = CryptographyRandom.Default.nextBytes(NONCE_SIZE)
        val plaintext = encodePayload(payload).encodeToByteArray()
        val ciphertext = cipher.encryptWithIv(nonce, plaintext, associatedData(ENVELOPE_VERSION, keyId))
        return Envelope(
            v = ENVELOPE_VERSION,
            keyId = keyId,
            nonce = Base64.encode(nonce),
            ciphertext = Base64.encode(ciphertext),
        )
    }

    @OptIn(DelicateCryptographyApi::class)
    suspend fun open(envelope: Envelope): Payload {
        if (envelope.keyId != keyId) {
            throw KeyIdMismatchException(expected = keyId, actual = envelope.keyId)
        }
        val plaintext = try {
            val nonce = Base64.decode(envelope.nonce)
            val ciphertext = Base64.decode(envelope.ciphertext)
            cipher.decryptWithIv(nonce, ciphertext, associatedData(envelope.v, envelope.keyId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DecryptionException("failed to decrypt envelope", e)
        }
        return decodePayload(plaintext.decodeToString())
    }

    private fun associatedData(v: Int, keyId: String): ByteArray =
        "peranta:v=$v:keyId=$keyId".encodeToByteArray()
}
