package to.sava.peranta.timeline

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import to.sava.peranta.model.FilePayload
import to.sava.peranta.model.MessagePayload
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
    /** 発出元の元通知が既に消えているか（dismiss コマンド受信でマーク、§3.4）。 */
    val sourceDismissed: Boolean = false,
) : TimelineItem()

/**
 * 他端末から受信した画像・ファイルの転送（§4.3）。相手側の吹き出しとして扱う。
 * 本体はまだダウンロードせず、[payload] の [FilePayload.attachments] 参照だけを保持する（判断4）。
 * ダウンロード状態はキャッシュの有無と転送進捗から画面側が導出する。
 */
@Serializable
@SerialName("receivedFile")
data class ReceivedFile(
    override val id: String,
    override val timestampEpochMillis: Long,
    val payload: FilePayload,
    override val expiresAtEpochMillis: Long? = null,
) : TimelineItem()

/** 他端末から受信したメッセージ（§4.1 message）。相手側の吹き出しとして扱う。 */
@Serializable
@SerialName("receivedMessage")
data class ReceivedMessage(
    override val id: String,
    override val timestampEpochMillis: Long,
    val payload: MessagePayload,
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

/**
 * エラーを誘発できる主体（§10.5）。発生回数を誰が握っているかで抑止の単位が変わるため、
 * 種別ごとの性質を [ErrorKind] の定義側に持たせる。
 */
enum class ErrorOrigin {
    /**
     * 復号より前の外部入力から生じる。鍵を持たない第三者でも、トピックへ publish するだけで
     * 任意回数発生させられる。種別ごとに窓 1 件へ抑える対象。
     */
    UNTRUSTED_INPUT,

    /**
     * 自端末の操作、または復号を通った入力（＝共有鍵を持つ自分の端末）から生じる。
     * 発生回数は利用者の操作に律速されるため、同一文言の連続だけを抑え、別々の失敗はそれぞれ見せる。
     */
    LOCAL_OPERATION,
}

/** エラーの発生種別。 */
@Serializable
enum class ErrorKind(val origin: ErrorOrigin) {
    @SerialName("envelopeDecode")
    ENVELOPE_DECODE(ErrorOrigin.UNTRUSTED_INPUT),

    @SerialName("keyIdMismatch")
    KEY_ID_MISMATCH(ErrorOrigin.UNTRUSTED_INPUT),

    @SerialName("decryption")
    DECRYPTION(ErrorOrigin.UNTRUSTED_INPUT),

    @SerialName("unknownType")
    UNKNOWN_TYPE(ErrorOrigin.UNTRUSTED_INPUT),

    @SerialName("commandExecution")
    COMMAND_EXECUTION(ErrorOrigin.LOCAL_OPERATION),

    /** 自端末が転送していない通知への操作を拒んだ（§3.4）。実行そのものの失敗とは区別する。 */
    @SerialName("commandUnauthorized")
    COMMAND_UNAUTHORIZED(ErrorOrigin.LOCAL_OPERATION),

    @SerialName("other")
    OTHER(ErrorOrigin.LOCAL_OPERATION),
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
