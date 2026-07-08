package to.sava.peranta.pairing

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PairingUriTest {

    private fun key(seed: Int = 7): ByteArray = ByteArray(32) { (it + seed).toByte() }

    private val validKeyParam: String = Base64.UrlSafe.encode(key())

    private fun uri(vararg params: Pair<String, String>): String =
        "peranta://pair?" + params.joinToString("&") { "${it.first}=${it.second}" }

    private fun decodeSuccess(uri: String): PairingData {
        val result = PairingUri.decode(uri)
        assertIs<PairingResult.Success>(result)
        return result.data
    }

    private fun decodeError(uri: String): PairingError {
        val result = PairingUri.decode(uri)
        assertIs<PairingResult.Failure>(result)
        return result.error
    }

    /** 全フィールドを含む URI は encode→decode で元に戻る。 */
    @Test
    fun encodeDecodeRoundTripsAllFields() {
        val data = PairingData(
            host = "peranta.sava.to",
            token = "tk_abc123",
            keyId = "k1",
            key = key(),
            tls = true,
            port = 8443,
        )
        assertEquals(data, decodeSuccess(PairingUri.encode(data)))
    }

    /** port を省略した URI は port=null・tls は指定どおりに戻る。 */
    @Test
    fun optionalPortAbsentDecodesToNull() {
        val data = PairingData("h", "t", "k1", key(), tls = false, port = null)
        val decoded = decodeSuccess(PairingUri.encode(data))
        assertEquals(null, decoded.port)
        assertFalse(decoded.tls)
    }

    /** host・token・keyId に含まれる予約文字や日本語も URL エンコードで壊れずに往復する。 */
    @Test
    fun specialCharactersSurviveRoundTrip() {
        val data = PairingData(
            host = "ホスト.example/パス?a=b&c=d",
            token = "tok en+/=&?#あ",
            keyId = "鍵 1",
            key = key(3),
        )
        assertEquals(data, decodeSuccess(PairingUri.encode(data)))
    }

    /** 0x00〜0xFF を含む鍵バイト列も base64url で完全に復元される。 */
    @Test
    fun keyWithWideByteRangeRoundTrips() {
        val bytes = ByteArray(32) { (it * 8 - 128).toByte() }
        val data = PairingData("h", "t", "k", bytes)
        assertTrue(data.key.contentEquals(decodeSuccess(PairingUri.encode(data)).key))
    }

    /** encode は固定 scheme とバージョンで始まり、秘密の生値をそのまま含めない。 */
    @Test
    fun encodeUsesSchemeVersionAndEncodesKey() {
        val data = PairingData("h", "plain token", "k1", key())
        val uri = PairingUri.encode(data)
        assertTrue(uri.startsWith("peranta://pair?"))
        assertTrue(uri.contains("v=1"))
        assertFalse(uri.contains("plain token"))
    }

    /** scheme が無い文字列は Malformed。 */
    @Test
    fun malformedUriFails() {
        assertIs<PairingError.Malformed>(decodeError("garbage-no-scheme"))
    }

    /** peranta 以外の scheme は WrongScheme。 */
    @Test
    fun wrongSchemeFails() {
        assertIs<PairingError.WrongScheme>(
            decodeError("https://pair?v=1&host=h&token=t&keyId=k&key=$validKeyParam"),
        )
    }

    /** 対応しないバージョンは値付きで UnsupportedVersion。 */
    @Test
    fun unsupportedVersionFails() {
        val error = decodeError(uri("v" to "2", "host" to "h", "token" to "t", "keyId" to "k", "key" to validKeyParam))
        assertIs<PairingError.UnsupportedVersion>(error)
        assertEquals("2", error.value)
        assertTrue(error.reason.contains("2"))
    }

    /** バージョン省略も UnsupportedVersion（値は null）。 */
    @Test
    fun missingVersionFails() {
        val error = decodeError(uri("host" to "h", "token" to "t", "keyId" to "k", "key" to validKeyParam))
        assertIs<PairingError.UnsupportedVersion>(error)
        assertEquals(null, error.value)
    }

    /** 必須フィールド欠落はどの項目が欠けたかを保持する。 */
    @Test
    fun missingRequiredFieldsFail() {
        val cases = mapOf(
            "host" to uri("v" to "1", "token" to "t", "keyId" to "k", "key" to validKeyParam),
            "token" to uri("v" to "1", "host" to "h", "keyId" to "k", "key" to validKeyParam),
            "keyId" to uri("v" to "1", "host" to "h", "token" to "t", "key" to validKeyParam),
            "key" to uri("v" to "1", "host" to "h", "token" to "t", "keyId" to "k"),
        )
        cases.forEach { (field, uri) ->
            val error = decodeError(uri)
            assertIs<PairingError.MissingField>(error)
            assertEquals(field, error.field)
        }
    }

    /** 空値の必須フィールドも欠落として扱う。 */
    @Test
    fun blankRequiredFieldIsMissing() {
        val error = decodeError(uri("v" to "1", "host" to "", "token" to "t", "keyId" to "k", "key" to validKeyParam))
        assertIs<PairingError.MissingField>(error)
        assertEquals("host", error.field)
    }

    /** base64 として解けない鍵は InvalidKeyEncoding。 */
    @Test
    fun invalidKeyEncodingFails() {
        assertIs<PairingError.InvalidKeyEncoding>(
            decodeError(uri("v" to "1", "host" to "h", "token" to "t", "keyId" to "k", "key" to "!!not-base64!!")),
        )
    }

    /** 32 バイトでない鍵は長さ付きで InvalidKeyLength。 */
    @Test
    fun invalidKeyLengthFails() {
        val short = Base64.UrlSafe.encode(ByteArray(16))
        val error = decodeError(uri("v" to "1", "host" to "h", "token" to "t", "keyId" to "k", "key" to short))
        assertIs<PairingError.InvalidKeyLength>(error)
        assertEquals(16, error.actual)
    }

    /** tls が真偽値でなければ InvalidTls。 */
    @Test
    fun invalidTlsFails() {
        val error = decodeError(
            uri("v" to "1", "host" to "h", "token" to "t", "keyId" to "k", "key" to validKeyParam, "tls" to "maybe"),
        )
        assertIs<PairingError.InvalidTls>(error)
        assertEquals("maybe", error.value)
    }

    /** port が整数でなければ InvalidPort。 */
    @Test
    fun invalidPortFails() {
        val error = decodeError(
            uri("v" to "1", "host" to "h", "token" to "t", "keyId" to "k", "key" to validKeyParam, "port" to "abc"),
        )
        assertIs<PairingError.InvalidPort>(error)
        assertEquals("abc", error.value)
    }

    /** 不正な %エンコード（末尾が不完全な HEX escape）は例外を漏らさず Malformed になる。 */
    @Test
    fun trailingPercentFails() {
        assertIs<PairingError.Malformed>(
            decodeError("peranta://pair?v=1&host=h%"),
        )
    }

    /** 不正な %エンコード（1 桁しかない HEX escape）は例外を漏らさず Malformed になる。 */
    @Test
    fun incompletePercentEscapeFails() {
        assertIs<PairingError.Malformed>(
            decodeError("peranta://pair?v=1&host=h%2"),
        )
    }

    /** 不正な %エンコード（HEX でない文字）は例外を漏らさず Malformed になる。 */
    @Test
    fun invalidPercentEscapeFails() {
        assertIs<PairingError.Malformed>(
            decodeError("peranta://pair?v=1&host=%zz"),
        )
    }

    /** 不正な %エンコードの Failure は、元のクエリ文字列に含まれる token・key の生値を漏らさない。 */
    @Test
    fun malformedPercentEncodingDoesNotLeakSecrets() {
        val secretToken = "tk_super_secret_value"
        val secretKey = "kk_super_secret_key_value"
        val uri = "peranta://pair?v=1&host=%zz&token=$secretToken&key=$secretKey"
        val error = decodeError(uri)
        assertIs<PairingError.Malformed>(error)
        assertFalse(error.reason.contains(secretToken))
        assertFalse(error.reason.contains(secretKey))
    }

    /** port が範囲外（0 / 65536 / 負値）は InvalidPort になる。 */
    @Test
    fun portOutOfRangeFails() {
        listOf("0", "65536", "-1").forEach { invalidPort ->
            val error = decodeError(
                uri(
                    "v" to "1",
                    "host" to "h",
                    "token" to "t",
                    "keyId" to "k",
                    "key" to validKeyParam,
                    "port" to invalidPort,
                ),
            )
            assertIs<PairingError.InvalidPort>(error)
            assertEquals(invalidPort, error.value)
        }
    }

    /** port の境界値（1 / 65535）は成功として通る。 */
    @Test
    fun portBoundaryValuesSucceed() {
        listOf(1, 65535).forEach { boundaryPort ->
            val data = decodeSuccess(
                uri(
                    "v" to "1",
                    "host" to "h",
                    "token" to "t",
                    "keyId" to "k",
                    "key" to validKeyParam,
                    "port" to boundaryPort.toString(),
                ),
            )
            assertEquals(boundaryPort, data.port)
        }
    }

    /** クエリの無い `peranta://pair` は WrongScheme ではなく MissingField になる。 */
    @Test
    fun schemeWithoutQueryIsNotWrongScheme() {
        val error = decodeError("peranta://pair")
        assertFalse(error is PairingError.WrongScheme)
        assertIs<PairingError.MissingField>(error)
    }

    /** 長すぎる version 値は reason に無制限では埋め込まれず切り詰められる。 */
    @Test
    fun unsupportedVersionWithLongValueTruncatesReason() {
        val longJunk = "x".repeat(200)
        val error = decodeError(
            uri("v" to longJunk, "host" to "h", "token" to "t", "keyId" to "k", "key" to validKeyParam),
        )
        assertIs<PairingError.UnsupportedVersion>(error)
        assertEquals(longJunk, error.value)
        assertFalse(error.reason.contains(longJunk))
        assertTrue(error.reason.contains(longJunk.take(16)))
    }

    /** toString は token と鍵を伏せ、host・keyId は残す。 */
    @Test
    fun toStringMasksSecrets() {
        val data = PairingData("peranta.sava.to", "super-secret-token", "k1", key())
        val text = data.toString()
        assertFalse(text.contains("super-secret-token"))
        assertFalse(text.contains(Base64.UrlSafe.encode(key())))
        assertFalse(text.contains(Base64.encode(key())))
        assertTrue(text.contains("peranta.sava.to"))
        assertTrue(text.contains("k1"))
        assertTrue(text.contains("***"))
    }

    /** equals / hashCode は全フィールド（鍵は内容比較）を考慮する。 */
    @Test
    fun equalityConsidersAllFields() {
        val base = PairingData("h", "t", "k", key())
        assertEquals(base, base)
        assertEquals(base, PairingData("h", "t", "k", key()))
        assertEquals(base.hashCode(), PairingData("h", "t", "k", key()).hashCode())
        assertNotEquals(base, PairingData("h2", "t", "k", key()))
        assertNotEquals(base, PairingData("h", "t2", "k", key()))
        assertNotEquals(base, PairingData("h", "t", "k2", key()))
        assertNotEquals(base, PairingData("h", "t", "k", key(9)))
        assertNotEquals(base, PairingData("h", "t", "k", key(), tls = false))
        assertNotEquals(base, PairingData("h", "t", "k", key(), port = 1))
        assertFalse(base.equals("not-a-pairing"))
    }
}
