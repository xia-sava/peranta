package to.sava.peranta.timeline

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import to.sava.peranta.model.Payload

/**
 * タイムラインに並ぶ 1 アイテム（§10.1）。
 * 受信通知・送信通知・エラーを同一リストに時系列で並べる。
 * 将来のメッセージ種別も同じ sealed 階層に追加する。
 */
@Serializable
sealed class TimelineItem {
    abstract val id: String

    /** アイテムがタイムラインに置かれた時刻（受信時刻・送信時刻・発生時刻）。 */
    abstract val timestampEpochMillis: Long

    /** 剪定判定に使う失効時刻。null なら失効しない。 */
    abstract val expiresAtEpochMillis: Long?
}

/** 他端末から受信した通知（相手側の吹き出し）。 */
@Serializable
@SerialName("received")
data class ReceivedNotification(
    override val id: String,
    override val timestampEpochMillis: Long,
    val payload: Payload,
    override val expiresAtEpochMillis: Long? = null,
) : TimelineItem()

/** 自端末が転送した通知（自分側の吹き出し）。M4 で使う。 */
@Serializable
@SerialName("sent")
data class SentNotification(
    override val id: String,
    override val timestampEpochMillis: Long,
    val payload: Payload,
    override val expiresAtEpochMillis: Long? = null,
) : TimelineItem()

/** エラーの発生種別。 */
@Serializable
enum class ErrorKind {
    @SerialName("envelopeDecode")
    ENVELOPE_DECODE,

    @SerialName("keyIdMismatch")
    KEY_ID_MISMATCH,

    @SerialName("decryption")
    DECRYPTION,

    @SerialName("unknownType")
    UNKNOWN_TYPE,

    @SerialName("other")
    OTHER,
}

/** 送信失敗・復号失敗等のエラー（自分側の吹き出しとしてエラー文言を表示）。 */
@Serializable
@SerialName("error")
data class ErrorItem(
    override val id: String,
    override val timestampEpochMillis: Long,
    val message: String,
    val kind: ErrorKind,
    override val expiresAtEpochMillis: Long? = null,
) : TimelineItem()
