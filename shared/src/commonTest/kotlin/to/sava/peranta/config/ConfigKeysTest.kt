package to.sava.peranta.config

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import to.sava.peranta.blob.drainToBytes
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.model.BlobEnc
import to.sava.peranta.model.NotificationPayload
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigKeysTest {

    private val keyBytes = generateKey()
    private val keyId = "k1"
    private val config = PerantaConfig(sharedKeyBase64 = Base64.encode(keyBytes), keyId = keyId)

    private val payload = NotificationPayload(
        id = "n1",
        from = "phone",
        to = "desktop",
        sentAtEpochMillis = 1_000,
        packageName = "com.example.app",
        appName = "Example",
        title = "Title",
        text = "Body",
        notificationKey = "0|com.example.app|1|tag|10",
        postedAtEpochMillis = 900,
    )

    /** sharedKeyBytes は設定の base64 を元のバイト列へ戻す。 */
    @Test
    fun sharedKeyBytesDecodesConfiguredKey() {
        assertContentEquals(keyBytes, config.sharedKeyBytes())
    }

    /** 共有鍵が未設定なら sharedKeyBytes は IllegalStateException を投げる。 */
    @Test
    fun sharedKeyBytesFailsWithoutSharedKey() {
        assertFailsWith<IllegalStateException> { config.copy(sharedKeyBase64 = null).sharedKeyBytes() }
    }

    /** keyId が未設定なら requireKeyId は IllegalStateException を投げ、設定済みなら値を返す。 */
    @Test
    fun requireKeyIdReturnsConfiguredKeyIdAndFailsWithoutIt() {
        assertEquals(keyId, config.requireKeyId())
        assertFailsWith<IllegalStateException> { config.copy(keyId = null).requireKeyId() }
    }

    /** messageCipher は設定の keyId を持ち、別々に作った暗号器同士で封緘と開封が往復する。 */
    @Test
    fun messageCipherRoundTripsAndCarriesKeyId() = runTest {
        val sealer = config.messageCipher()
        val envelope = sealer.seal(payload)

        assertEquals(keyId, envelope.keyId)
        assertEquals(payload, config.messageCipher().open(envelope))
    }

    /** messageCipher は共有鍵と keyId のどちらが欠けても IllegalStateException を投げる。 */
    @Test
    fun messageCipherFailsWhenKeyMaterialIsMissing() {
        assertFailsWith<IllegalStateException> { config.copy(sharedKeyBase64 = null).messageCipher() }
        assertFailsWith<IllegalStateException> { config.copy(keyId = null).messageCipher() }
    }

    /** blobCipher は設定の keyId を持ち、別々に作った暗号器同士で暗号化と復号が往復する。 */
    @Test
    fun blobCipherRoundTripsAndCarriesKeyId() = runTest {
        val plain = "blob body".encodeToByteArray()
        val encryptor = config.blobCipher()
        var enc: BlobEnc? = null
        val ciphertext = drainToBytes { output ->
            enc = encryptor.encrypt("blob-1", ByteReadChannel(plain), output, plain.size.toLong())
        }

        assertEquals(keyId, encryptor.keyId)
        val decrypted = drainToBytes { output ->
            config.blobCipher().decrypt("blob-1", enc!!, plain.size.toLong(), ByteReadChannel(ciphertext), output)
        }
        assertContentEquals(plain, decrypted)
    }

    /** blobCipher は共有鍵と keyId のどちらが欠けても IllegalStateException を投げる。 */
    @Test
    fun blobCipherFailsWhenKeyMaterialIsMissing() {
        assertFailsWith<IllegalStateException> { config.copy(sharedKeyBase64 = null).blobCipher() }
        assertFailsWith<IllegalStateException> { config.copy(keyId = null).blobCipher() }
    }
}
