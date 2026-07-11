package to.sava.peranta.pairing

import io.ktor.http.URLDecodeException
import io.ktor.http.encodeURLQueryComponent
import io.ktor.http.parseQueryString
import kotlin.io.encoding.Base64

/** ペアリング URI の固定 scheme + authority 部（クエリを含まない）。 */
private const val SCHEME_AUTHORITY: String = "peranta://pair"

/** ペアリング URI の固定 scheme + authority 部（この直後にクエリが続く）。 */
private const val SCHEME_PREFIX: String = "$SCHEME_AUTHORITY?"

/** port として許容する範囲。 */
private val VALID_PORT_RANGE: IntRange = 1..65535

/** 現行のペアリングプロトコルバージョン。 */
private const val PAIRING_VERSION: String = "1"

/** 共有鍵の長さ（バイト）。 */
private const val SHARED_KEY_SIZE: Int = 32

private const val PARAM_VERSION: String = "v"
private const val PARAM_HOST: String = "host"
private const val PARAM_TOKEN: String = "token"
private const val PARAM_KEY_ID: String = "keyId"
private const val PARAM_KEY: String = "key"
private const val PARAM_TLS: String = "tls"
private const val PARAM_PORT: String = "port"
private const val PARAM_CONTROL_TOPIC: String = "ctl"
private const val PARAM_BLOB_TOPIC: String = "blob"

/**
 * ペアリング URI の符号化・復号（§6）。QR に載る中身そのもの。
 * key は URL セーフ base64 で表し、全クエリ値を URL エンコードする。
 */
object PairingUri {

    /** 共有鍵の URL セーフ base64 表現に使う。 */
    private val base64: Base64 = Base64.UrlSafe

    /** [data] を `peranta://pair?...` 形式へ符号化する。 */
    fun encode(data: PairingData): String {
        val params = buildList {
            add(PARAM_VERSION to PAIRING_VERSION)
            add(PARAM_HOST to data.host)
            add(PARAM_TOKEN to data.token)
            add(PARAM_KEY_ID to data.keyId)
            add(PARAM_KEY to base64.encode(data.key))
            add(PARAM_TLS to data.tls.toString())
            data.port?.let { add(PARAM_PORT to it.toString()) }
            data.controlTopic?.let { add(PARAM_CONTROL_TOPIC to it) }
            data.blobTopic?.let { add(PARAM_BLOB_TOPIC to it) }
        }
        val query = params.joinToString("&") { (name, value) ->
            "$name=${value.encodeURLQueryComponent(encodeFull = true)}"
        }
        return SCHEME_PREFIX + query
    }

    /** [uri] を検証しつつ [PairingData] へ復号する。失敗は理由付きで返す。 */
    fun decode(uri: String): PairingResult {
        if (uri == SCHEME_AUTHORITY) {
            return PairingResult.Failure(PairingError.MissingField(PARAM_HOST))
        }
        if (!uri.startsWith(SCHEME_PREFIX)) {
            return PairingResult.Failure(schemeErrorFor(uri))
        }
        val params = try {
            parseQueryString(uri.substring(SCHEME_PREFIX.length))
        } catch (error: URLDecodeException) {
            return PairingResult.Failure(PairingError.Malformed)
        } catch (error: IllegalArgumentException) {
            return PairingResult.Failure(PairingError.Malformed)
        }

        val version = params[PARAM_VERSION]
        if (version != PAIRING_VERSION) {
            return PairingResult.Failure(PairingError.UnsupportedVersion(version))
        }

        val host = params.requiredOrNull(PARAM_HOST)
            ?: return PairingResult.Failure(PairingError.MissingField(PARAM_HOST))
        val token = params.requiredOrNull(PARAM_TOKEN)
            ?: return PairingResult.Failure(PairingError.MissingField(PARAM_TOKEN))
        val keyId = params.requiredOrNull(PARAM_KEY_ID)
            ?: return PairingResult.Failure(PairingError.MissingField(PARAM_KEY_ID))
        val keyEncoded = params.requiredOrNull(PARAM_KEY)
            ?: return PairingResult.Failure(PairingError.MissingField(PARAM_KEY))

        val key = decodeKey(keyEncoded)
            ?: return PairingResult.Failure(PairingError.InvalidKeyEncoding)
        if (key.size != SHARED_KEY_SIZE) {
            return PairingResult.Failure(PairingError.InvalidKeyLength(key.size))
        }

        val tls = when (val raw = params[PARAM_TLS]) {
            null, "true" -> true
            "false" -> false
            else -> return PairingResult.Failure(PairingError.InvalidTls(raw))
        }

        val port = params[PARAM_PORT]?.let { raw ->
            val parsed = raw.toIntOrNull() ?: return PairingResult.Failure(PairingError.InvalidPort(raw))
            if (parsed !in VALID_PORT_RANGE) {
                return PairingResult.Failure(PairingError.InvalidPort(raw))
            }
            parsed
        }

        val controlTopic = params.requiredOrNull(PARAM_CONTROL_TOPIC)
        val blobTopic = params.requiredOrNull(PARAM_BLOB_TOPIC)

        return PairingResult.Success(
            PairingData(
                host = host,
                token = token,
                keyId = keyId,
                key = key,
                tls = tls,
                port = port,
                controlTopic = controlTopic,
                blobTopic = blobTopic,
            ),
        )
    }

    private fun io.ktor.http.Parameters.requiredOrNull(name: String): String? =
        this[name]?.takeIf { it.isNotBlank() }

    private fun schemeErrorFor(uri: String): PairingError =
        if (uri.contains("://")) PairingError.WrongScheme else PairingError.Malformed

    private fun decodeKey(encoded: String): ByteArray? =
        try {
            base64.decode(encoded)
        } catch (error: IllegalArgumentException) {
            null
        }
}
