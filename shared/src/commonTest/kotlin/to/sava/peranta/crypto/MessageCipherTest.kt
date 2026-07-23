package to.sava.peranta.crypto

import kotlinx.coroutines.test.runTest
import to.sava.peranta.model.CommandPayload
import to.sava.peranta.model.CommandType
import to.sava.peranta.model.MessagePayload
import to.sava.peranta.model.NotificationActionDetail
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.PresencePayload
import to.sava.peranta.model.SemanticActionKind
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.model.encodeEnvelope
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MessageCipherTest {

    private val keyId = "key-1"
    private val key = generateKey()
    private val cipher = MessageCipher(key, keyId)

    private fun notification(id: String = "n1") = NotificationPayload(
        id = id,
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

    private val samplePayloads: List<Payload> = listOf(
        notification(),
        SmsPayload(
            id = "s1",
            from = "phone",
            to = "desktop",
            sentAtEpochMillis = 1_100,
            senderNumber = "+81901234567",
            text = "hi",
            postedAtEpochMillis = 1_000,
        ),
        CommandPayload(
            id = "c1",
            from = "desktop",
            to = "phone",
            sentAtEpochMillis = 1_200,
            command = CommandType.DISMISS,
            targetNotificationKey = "0|com.example.app|1|tag|10",
        ),
        PresencePayload(
            id = "p1",
            from = "phone",
            to = "*",
            sentAtEpochMillis = 1_300,
            deviceName = "Pixel",
            endpoint = "e",
        ),
        MessagePayload(
            id = "m1",
            from = "phone",
            to = "*",
            sentAtEpochMillis = 1_400,
            text = "会議は 15 時からです",
            fromName = "xia-phone",
        ),
    )

    /** 全 Payload 種別で seal/open のラウンドトリップが元の値と一致することを検証する。 */
    @Test
    fun sealOpenRoundTripAllTypes() = runTest {
        samplePayloads.forEach { payload ->
            val envelope = cipher.seal(payload)
            assertEquals(payload, cipher.open(envelope))
        }
    }

    /** 暗号文の改竄が DecryptionException になることを検証する。 */
    @Test
    fun tamperedCiphertextFailsDecryption() = runTest {
        val envelope = cipher.seal(notification())
        val bytes = Base64.decode(envelope.ciphertext)
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        val tampered = envelope.copy(ciphertext = Base64.encode(bytes))
        assertFailsWith<DecryptionException> { cipher.open(tampered) }
    }

    /** nonce の改竄が DecryptionException になることを検証する。 */
    @Test
    fun tamperedNonceFailsDecryption() = runTest {
        val envelope = cipher.seal(notification())
        val nonce = Base64.decode(envelope.nonce)
        nonce[0] = (nonce[0].toInt() xor 0x01).toByte()
        val tampered = envelope.copy(nonce = Base64.encode(nonce))
        assertFailsWith<DecryptionException> { cipher.open(tampered) }
    }

    /** keyId 不一致が復号を試みる前に KeyIdMismatchException になることを検証する。 */
    @Test
    fun mismatchedKeyIdFailsBeforeDecryption() = runTest {
        val envelope = cipher.seal(notification())
        val other = MessageCipher(key, "key-2")
        val error = assertFailsWith<KeyIdMismatchException> { other.open(envelope) }
        assertEquals("key-2", error.expected)
        assertEquals("key-1", error.actual)
    }

    /** 異なる鍵での復号が DecryptionException になることを検証する。 */
    @Test
    fun differentKeyCannotDecrypt() = runTest {
        val envelope = cipher.seal(notification())
        val other = MessageCipher(generateKey(), keyId)
        assertFailsWith<DecryptionException> { other.open(envelope) }
    }

    /** v の改竄が AAD 束縛を破り DecryptionException になることを検証する。 */
    @Test
    fun tamperedVersionBreaksAadBinding() = runTest {
        val envelope = cipher.seal(notification())
        val tampered = envelope.copy(v = envelope.v + 1)
        assertFailsWith<DecryptionException> { cipher.open(tampered) }
    }

    /** keyId の改竄が AAD 束縛を破り DecryptionException になることを検証する。 */
    @Test
    fun tamperedKeyIdBreaksAadBinding() = runTest {
        val envelope = cipher.seal(notification())
        val rotated = MessageCipher(key, "key-2")
        val tampered = envelope.copy(keyId = "key-2")
        assertFailsWith<DecryptionException> { rotated.open(tampered) }
    }

    /** generateKey が 32 バイトの鍵を返し、呼び出しごとに異なる値になることを検証する。 */
    @Test
    fun generateKeyIsThirtyTwoBytesAndDistinct() {
        val a = generateKey()
        val b = generateKey()
        assertEquals(32, a.size)
        assertEquals(32, b.size)
        assertNotEquals(Base64.encode(a), Base64.encode(b))
    }

    /** 同一 Payload を 2 回 seal しても nonce と暗号文が毎回異なり、双方とも復号できることを検証する。 */
    @Test
    fun sealingTwiceProducesDifferentNonceAndCiphertext() = runTest {
        val payload = notification()
        val first = cipher.seal(payload)
        val second = cipher.seal(payload)
        assertNotEquals(first.nonce, second.nonce)
        assertNotEquals(first.ciphertext, second.ciphertext)
        assertEquals(payload, cipher.open(first))
        assertEquals(payload, cipher.open(second))
    }

    /** コンストラクタに渡した鍵バイト配列を後から変更しても復号結果に影響しないことを検証する。 */
    @Test
    fun keyIsCopiedDefensively() = runTest {
        val mutableKey = generateKey()
        val local = MessageCipher(mutableKey, "key-x")
        val envelope = local.seal(notification())
        mutableKey.fill(0)
        assertEquals(notification(), local.open(envelope))
    }

    /** 日本語・絵文字を含む本文が seal/open のラウンドトリップで完全に一致することを検証する。 */
    @Test
    fun sealOpenRoundTripWithJapaneseAndEmoji() = runTest {
        val payload = SmsPayload(
            id = "s-ja",
            from = "phone",
            to = "desktop",
            sentAtEpochMillis = 1_400,
            senderNumber = "+81901234567",
            senderName = "田中太郎",
            text = "確認コード: 123456 です 🎉📱✨",
            postedAtEpochMillis = 1_300,
        )
        val envelope = cipher.seal(payload)
        assertEquals(payload, cipher.open(envelope))
    }

    /** 空文字列フィールドを持つ Payload が seal/open のラウンドトリップで一致することを検証する。 */
    @Test
    fun sealOpenRoundTripWithEmptyStringField() = runTest {
        val payload = SmsPayload(
            id = "s-empty",
            from = "phone",
            to = "desktop",
            sentAtEpochMillis = 1_500,
            senderNumber = "+81901234567",
            text = "",
            postedAtEpochMillis = 1_400,
        )
        val envelope = cipher.seal(payload)
        assertEquals(payload, cipher.open(envelope))
    }

    /** 数 KB 級の大きな本文を持つ Payload が seal/open のラウンドトリップで一致することを検証する。 */
    @Test
    fun sealOpenRoundTripWithLargePayload() = runTest {
        val largeText = buildString {
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            repeat(4_096) { append(chars[it % chars.length]) }
        }
        val payload = SmsPayload(
            id = "s-large",
            from = "phone",
            to = "desktop",
            sentAtEpochMillis = 1_600,
            senderNumber = "+81901234567",
            text = largeText,
            postedAtEpochMillis = 1_500,
        )
        val envelope = cipher.seal(payload)
        assertEquals(payload, cipher.open(envelope))
    }

    /** nonce が不正な base64 の Envelope を開くと DecryptionException になることを検証する。 */
    @Test
    fun openWithInvalidBase64NonceThrowsDecryptionException() = runTest {
        val envelope = cipher.seal(notification())
        val tampered = envelope.copy(nonce = "not-valid-base64!!!")
        assertFailsWith<DecryptionException> { cipher.open(tampered) }
    }

    /** actionDetails（§3.4）を持つ NotificationPayload が seal/open のラウンドトリップで一致することを検証する。 */
    @Test
    fun sealOpenRoundTripWithActionDetails() = runTest {
        val payload = notification(id = "n-actions").copy(
            actions = listOf("返信", "地図"),
            actionDetails = listOf(
                NotificationActionDetail(semanticAction = SemanticActionKind.REPLY, hasRemoteInput = true),
                NotificationActionDetail(opensActivity = true),
            ),
        )
        val envelope = cipher.seal(payload)
        assertEquals(payload, cipher.open(envelope))
    }

    /** ciphertext が不正な base64 の Envelope を開くと DecryptionException になることを検証する。 */
    @Test
    fun openWithInvalidBase64CiphertextThrowsDecryptionException() = runTest {
        val envelope = cipher.seal(notification())
        val tampered = envelope.copy(ciphertext = "not-valid-base64!!!")
        assertFailsWith<DecryptionException> { cipher.open(tampered) }
    }

    /** seal した Envelope の JSON 文字列に "v":1 が明示されることを検証する（encodeDefaults の回帰防止）。 */
    @Test
    fun sealedEnvelopeJsonContainsExplicitVersion() = runTest {
        val envelope = cipher.seal(notification())
        val json = encodeEnvelope(envelope)
        assertTrue(json.contains("\"v\":1"), json)
    }
}
