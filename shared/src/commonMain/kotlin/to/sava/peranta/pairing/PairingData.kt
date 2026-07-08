package to.sava.peranta.pairing

/**
 * QR ペアリングで受け渡す設定一式（§6）。
 * host / token / keyId と 32 バイトの共有鍵、任意で TLS 可否・ポート・control topic を持つ。
 * [controlTopic] は全端末共有の presence/ロスター用 topic（§8）で、設定元端末が確定して配布する。
 * token と key は秘密の塊のため、[toString] では伏せてログ漏れを防ぐ。
 */
class PairingData(
    val host: String,
    val token: String,
    val keyId: String,
    val key: ByteArray,
    val tls: Boolean = true,
    val port: Int? = null,
    val controlTopic: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingData) return false
        return host == other.host &&
            token == other.token &&
            keyId == other.keyId &&
            key.contentEquals(other.key) &&
            tls == other.tls &&
            port == other.port &&
            controlTopic == other.controlTopic
    }

    override fun hashCode(): Int {
        var result = host.hashCode()
        result = 31 * result + token.hashCode()
        result = 31 * result + keyId.hashCode()
        result = 31 * result + key.contentHashCode()
        result = 31 * result + tls.hashCode()
        result = 31 * result + (port ?: 0)
        result = 31 * result + (controlTopic?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "PairingData(host=$host, token=***, keyId=$keyId, key=***, tls=$tls, port=$port, controlTopic=$controlTopic)"
}

/** 失敗理由の文言に埋め込む生値の最大長（超過分は省略記号で切り詰める）。 */
private const val REASON_VALUE_MAX_LENGTH: Int = 16

/** [value] を失敗理由の文言用に切り詰める。null は "(なし)" と表示する。 */
private fun String?.truncatedForReason(): String =
    when {
        this == null -> "(なし)"
        length <= REASON_VALUE_MAX_LENGTH -> this
        else -> take(REASON_VALUE_MAX_LENGTH) + "…"
    }

/** [PairingUri.decode] の失敗理由（握り潰さず理由を保持する）。 */
sealed class PairingError(val reason: String) {

    /** URI として解析できない。 */
    object Malformed : PairingError("ペアリング URI を解析できません")

    /** scheme が peranta://pair ではない。 */
    object WrongScheme : PairingError("ペアリング URI の形式が不正です")

    /** 対応しないプロトコルバージョン。 */
    class UnsupportedVersion(val value: String?) :
        PairingError("対応しないペアリングバージョンです: ${value.truncatedForReason()}")

    /** 必須フィールドが欠落している。 */
    class MissingField(val field: String) :
        PairingError("必須項目が不足しています: $field")

    /** 共有鍵が base64 として不正。 */
    object InvalidKeyEncoding : PairingError("共有鍵の符号化が不正です")

    /** 共有鍵の長さが 32 バイトでない。 */
    class InvalidKeyLength(val actual: Int) :
        PairingError("共有鍵の長さが不正です: $actual バイト（32 バイト必須）")

    /** ポート指定が整数でない。 */
    class InvalidPort(val value: String) :
        PairingError("ポート指定が不正です: $value")

    /** TLS 指定が真偽値でない。 */
    class InvalidTls(val value: String) :
        PairingError("TLS 指定が不正です: $value")
}

/** [PairingUri.decode] の結果。成功なら [PairingData]、失敗なら [PairingError] を保持する。 */
sealed class PairingResult {
    class Success(val data: PairingData) : PairingResult()
    class Failure(val error: PairingError) : PairingResult()
}
