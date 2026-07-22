package to.sava.peranta.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** to フィールドに指定すると全端末宛を意味する。 */
const val BROADCAST_TARGET: String = "*"

/** 通知の優先度。JSON では小文字文字列で表現する。 */
@Serializable
enum class Priority {
    @SerialName("low")
    LOW,

    @SerialName("normal")
    NORMAL,

    @SerialName("high")
    HIGH,
}

/** 送信端末へ指示する操作の種類。 */
@Serializable
enum class CommandType {
    @SerialName("dismiss")
    DISMISS,

    @SerialName("invokeAction")
    INVOKE_ACTION,

    @SerialName("reply")
    REPLY,

    @SerialName("muteApp")
    MUTE_APP,

    @SerialName("unmuteApp")
    UNMUTE_APP,
}

/** 端末間で転送されるメッセージ本体。type フィールドで種別を判別する。 */
@Serializable
sealed class Payload {
    abstract val id: String
    abstract val from: String
    abstract val to: String
    abstract val sentAtEpochMillis: Long

    /** 送信元端末の表示名（§4.1）。旧バージョン由来のペイロードでは未設定（null）で、[from] の deviceId 表示に落ちる。 */
    open val fromName: String? get() = null
}

/** アプリ通知の転送。 */
@Serializable
@SerialName("notification")
data class NotificationPayload(
    override val id: String,
    override val from: String,
    override val to: String,
    override val sentAtEpochMillis: Long,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val notificationKey: String,
    val actions: List<String> = emptyList(),
    val postedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long? = null,
    val priority: Priority = Priority.NORMAL,
    val attachments: List<AttachmentRef> = emptyList(),
    override val fromName: String? = null,
) : Payload()

/** 画像・ファイルの転送（§4.3）。本体は暗号化 blob として別送し、[attachments] で参照する。 */
@Serializable
@SerialName("file")
data class FilePayload(
    override val id: String,
    override val from: String,
    override val to: String,
    override val sentAtEpochMillis: Long,
    val caption: String? = null,
    val attachments: List<AttachmentRef>,
    val postedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long? = null,
    val priority: Priority = Priority.NORMAL,
    override val fromName: String? = null,
) : Payload()

/** SMS の転送。 */
@Serializable
@SerialName("sms")
data class SmsPayload(
    override val id: String,
    override val from: String,
    override val to: String,
    override val sentAtEpochMillis: Long,
    val senderNumber: String,
    val senderName: String? = null,
    val text: String,
    val postedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long? = null,
    val priority: Priority = Priority.HIGH,
    val attachments: List<AttachmentRef> = emptyList(),
    override val fromName: String? = null,
) : Payload()

/** 送信元端末への操作指示。 */
@Serializable
@SerialName("command")
data class CommandPayload(
    override val id: String,
    override val from: String,
    override val to: String,
    override val sentAtEpochMillis: Long,
    val command: CommandType,
    val targetNotificationKey: String? = null,
    val actionIndex: Int? = null,
    val replyText: String? = null,
    val packageName: String? = null,
    val expiresAtEpochMillis: Long? = null,
) : Payload()

/** 端末の存在通知と能力の告知。 */
@Serializable
@SerialName("presence")
data class PresencePayload(
    override val id: String,
    override val from: String,
    override val to: String,
    override val sentAtEpochMillis: Long,
    val deviceName: String,
    val endpoint: String,
    val capabilities: List<String> = emptyList(),
    val sender: Boolean = false,
) : Payload()

/** 新しい Payload id 用の UUID 文字列を生成する。 */
@OptIn(ExperimentalUuidApi::class)
fun newPayloadId(): String = Uuid.random().toString()

/** 現在時刻をエポックミリ秒で返す。 */
fun nowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
