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

/** 封筒の版が受信側の知る版より新しい場合に投げる。端末の更新を促すため復号失敗と区別する。 */
class UnsupportedEnvelopeVersionException(val supported: Int, val actual: Int) :
    Exception("unsupported envelope version: supported=$supported actual=$actual")

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

    /**
     * 封筒を開いて Payload を取り出す。封筒の開け方・鍵・中身の順に確かめる。
     *
     * 自分の知る版より新しい封筒は開け方が分からないため拒む。古い版は受け入れる
     * （新旧の端末が混在する間も、更新済みの端末が未更新の端末からの封筒を開けるようにする）。
     * AAD は封筒が名乗る版で組む。封緘した版と同じ値でなければタグ検証が通らない。
     */
    @OptIn(DelicateCryptographyApi::class)
    suspend fun open(envelope: Envelope): Payload {
        if (envelope.v > ENVELOPE_VERSION) {
            throw UnsupportedEnvelopeVersionException(supported = ENVELOPE_VERSION, actual = envelope.v)
        }
        if (envelope.keyId != keyId) {
            throw KeyIdMismatchException(expected = keyId, actual = envelope.keyId)
        }
        val plaintext = try {
            cipher.decryptWithIv(
                checkedNonce(envelope.nonce),
                Base64.decode(envelope.ciphertext),
                associatedData(envelope.v, envelope.keyId),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DecryptionException("failed to decrypt envelope", e)
        }
        return decodePayload(plaintext.decodeToString())
    }

    /**
     * nonce を復号し、長さを確かめる。AES-GCM は 12 バイト以外の IV も受理する（GHASH で J0 を導出する）ため、
     * 受信側が確かめないと「同一鍵の下で nonce を再利用しない」という前提が静かに崩れても気づけない。
     */
    private fun checkedNonce(nonceBase64: String): ByteArray =
        Base64.decode(nonceBase64).also {
            require(it.size == NONCE_SIZE) { "nonce must be $NONCE_SIZE bytes, was ${it.size}" }
        }

    private fun associatedData(v: Int, keyId: String): ByteArray =
        "peranta:v=$v:keyId=$keyId".encodeToByteArray()
}
