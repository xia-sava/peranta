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

/**
 * 元通知がロック画面で見せてよい範囲（§4.1）。Android の `Notification.VISIBILITY_*` を
 * プラットフォーム非依存の 3 段へ写したもので、受信側はこの範囲を超えて内容を表示しない。
 */
@Serializable
enum class NotificationVisibility {
    /** ロック画面でも内容を出してよい。 */
    @SerialName("public")
    PUBLIC,

    /** ロック画面では発信元だけを出し、内容は伏せる。 */
    @SerialName("private")
    PRIVATE,

    /** ロック画面には出さない。 */
    @SerialName("secret")
    SECRET,
}

/**
 * 受信端末でミラー表示を払いのけた（スワイプ・× ボタン）ときに、あわせて何をするか（§3.3）。
 * 送信端末がアプリごとの設定（§7）から決めてペイロードに載せ、受信端末はこれに従う。
 * 判断を運ぶことで、受信端末ごとに設定を持たずに全端末で挙動が揃う。
 */
@Serializable
enum class SwipeBehavior {
    /** この端末の表示を引っ込めるだけ。元通知は残る。 */
    @SerialName("localOnly")
    LOCAL_ONLY,

    /** あわせて元端末と他の受信端末からも消す（既読同期、§3.4）。 */
    @SerialName("dismissSource")
    DISMISS_SOURCE,
}

/** 通知アクションの意味分類（Notification.Action#getSemanticAction の閉集合を写す、§3.4）。 */
@Serializable
enum class SemanticActionKind {
    @SerialName("reply")
    REPLY,

    @SerialName("markAsRead")
    MARK_AS_READ,

    @SerialName("markAsUnread")
    MARK_AS_UNREAD,

    @SerialName("delete")
    DELETE,

    @SerialName("archive")
    ARCHIVE,

    @SerialName("mute")
    MUTE,

    @SerialName("unmute")
    UNMUTE,

    @SerialName("thumbsUp")
    THUMBS_UP,

    @SerialName("thumbsDown")
    THUMBS_DOWN,

    @SerialName("call")
    CALL,
}

/** 通知アクション 1 個ぶんの分類シグナル（§3.4）。[NotificationPayload.actions] と index で対応する。 */
@Serializable
data class NotificationActionDetail(
    /** 投稿アプリが設定した意味分類。未設定（NONE）は null。 */
    val semanticAction: SemanticActionKind? = null,
    /** インライン返信入力（RemoteInput）を持つか。 */
    val hasRemoteInput: Boolean = false,
    /** 発火先が Activity か（true=画面が開く / false=broadcast・service / null=不明）。 */
    val opensActivity: Boolean? = null,
)

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

    @SerialName("setAppRule")
    SET_APP_RULE,
}

/**
 * アプリごとの転送の扱い（§7）。発信側の設定そのものを値として運び、受信端末からの変更に使う。
 * 項目が増えてもコマンドを増やさずに済むよう、扱いをまとめて 1 つの値として送る。
 */
@Serializable
data class AppRuleSettings(
    /** 転送するか。フィルタモードに依らない意味で持ち、受信側がモードに応じたルールへ写す。 */
    val forward: Boolean,
    /** 優先度の上書き。null なら推定した基準優先度のまま。 */
    val priorityOverride: Priority? = null,
    /** タイトル・本文を伏せて転送するか。 */
    val redact: Boolean = false,
    /** 受信端末で払いのけたら元通知も消すか（§3.3）。 */
    val swipeDismissesSource: Boolean = false,
)

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
    /** [actions] と同順のアクション分類シグナル（§3.4）。旧バージョン由来では空。 */
    val actionDetails: List<NotificationActionDetail> = emptyList(),
    val postedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long? = null,
    val priority: Priority = Priority.NORMAL,
    val attachments: List<AttachmentRef> = emptyList(),
    /**
     * 元通知の送信者アイコン（largeIcon、§4.3.1）。本文の画像と同じく暗号化 blob として別送する。
     * 表示側はバブルのヘッダやトーストのアイコンに使い、添付カードには出さないため
     * [attachments] とは別に持つ。
     */
    val senderIcon: AttachmentRef? = null,
    /**
     * 同一 [id] の改版番号（§4.3.1）。初回配送は 0 で、画像添付を後から足した配送で 1 以上になる。
     * 受信側は [id] と対で重複排除し、既存アイテムの差し替えとして扱う。
     */
    val revision: Int = 0,
    override val fromName: String? = null,
    /**
     * 元通知のロック画面可視性（§4.1）。旧バージョン由来のペイロードでは未設定（null）で、
     * 受信側は Android の既定と同じ [NotificationVisibility.PRIVATE] として扱う。
     */
    val visibility: NotificationVisibility? = null,
    /**
     * 受信端末で払いのけたときの扱い（§3.3 / §7）。旧バージョン由来のペイロードでは未設定（null）で、
     * 受信側は既定の [SwipeBehavior.LOCAL_ONLY] として扱う。
     */
    val swipeBehavior: SwipeBehavior? = null,
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
    /**
     * 対応する SMS アプリの通知（§3.1）。直接受信した時点では判明しないため、
     * その通知を重複として落とした時点で [revision] を上げた改版に載せる。
     * これが入っているアイテムだけが既読同期（§3.4）の対象になる。
     */
    val notificationKey: String? = null,
    val revision: Int = 0,
    override val fromName: String? = null,
) : Payload()

/** 端末間の自由文メッセージ（§4.1）。本文以外の実体（ファイル等）は [FilePayload] で送る。 */
@Serializable
@SerialName("message")
data class MessagePayload(
    override val id: String,
    override val from: String,
    override val to: String,
    override val sentAtEpochMillis: Long,
    val text: String,
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
    /** SET_APP_RULE が運ぶアプリごとの扱い（§7）。他の種別では未設定。 */
    val appRule: AppRuleSettings? = null,
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

/** 既読同期（§3.4）で操作する元通知の key。元通知に紐づかないペイロードでは null。 */
fun Payload.notificationKeyOrNull(): String? = when (this) {
    is NotificationPayload -> notificationKey
    is SmsPayload -> notificationKey
    else -> null
}

/**
 * 受信端末で払いのけたときの扱い（§3.3）。指示を運ばない（旧バージョン由来・元通知を持たない）
 * ペイロードは既定の [SwipeBehavior.LOCAL_ONLY] として扱う。
 */
fun Payload.swipeBehaviorOrDefault(): SwipeBehavior = when (this) {
    is NotificationPayload -> swipeBehavior ?: SwipeBehavior.LOCAL_ONLY
    else -> SwipeBehavior.LOCAL_ONLY
}

/** 改版番号（§4.3.1）。改版を持たないペイロードでは 0。 */
fun Payload.revisionOrZero(): Int = when (this) {
    is NotificationPayload -> revision
    is SmsPayload -> revision
    else -> 0
}

/** 新しい Payload id 用の UUID 文字列を生成する。 */
@OptIn(ExperimentalUuidApi::class)
fun newPayloadId(): String = Uuid.random().toString()

/** 現在時刻をエポックミリ秒で返す。 */
fun nowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
