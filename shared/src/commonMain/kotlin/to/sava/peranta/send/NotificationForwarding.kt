package to.sava.peranta.send

import co.touchlab.kermit.Logger
import to.sava.peranta.blob.MAX_FULL_TEXT_ATTACHMENT_BYTES
import to.sava.peranta.filter.DEFAULT_SYSTEM_PACKAGES
import to.sava.peranta.filter.FilterMode
import to.sava.peranta.filter.FilterRule
import to.sava.peranta.filter.decideFilter
import to.sava.peranta.filter.isOtpNotification
import to.sava.peranta.filter.payloadForPersistence
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.FULL_TEXT_PREVIEW_BYTES
import to.sava.peranta.model.MAX_ACTION_LABEL_BYTES
import to.sava.peranta.model.MAX_FORWARDED_ACTIONS
import to.sava.peranta.model.MAX_FORWARDED_TEXT_BYTES
import to.sava.peranta.model.MAX_FORWARDED_TITLE_BYTES
import to.sava.peranta.model.NotificationActionDetail
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.NotificationVisibility
import to.sava.peranta.model.Payload
import to.sava.peranta.model.Priority
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.model.newPayloadId
import to.sava.peranta.model.truncateToUtf8Bytes
import to.sava.peranta.model.utf8ByteLength

/** OTP 通知に付ける失効までの猶予（§4）。 */
const val OTP_TTL_MILLIS: Long = 5 * 60 * 1000L

/** SMS 転送に付ける失効までの猶予（§3.1）。 */
const val SMS_TTL_MILLIS: Long = 10 * 60 * 1000L

/** 伏せ字適用時にタイトル・本文へ入れる文字列。 */
const val REDACTED_PLACEHOLDER: String = "（内容は伏せられています）"

/** 捕捉した通知から取り出した素の値。sbn 抽出とフィルタ判定を分離するための入力。 */
data class NotificationInput(
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val notificationKey: String,
    val actions: List<String> = emptyList(),
    /** [actions] と同順のアクション分類シグナル（§3.4）。 */
    val actionDetails: List<NotificationActionDetail> = emptyList(),
    val postedAtEpochMillis: Long,
    val priority: Priority = Priority.NORMAL,
    /** 元通知のロック画面可視性（§4.1）。 */
    val visibility: NotificationVisibility = NotificationVisibility.PRIVATE,
)

/**
 * 転送用に組んだ通知と、伏せ字適用後・切り詰め前の本文全文（§4.3）。
 * [fullText] は全文添付の要否判定（[shouldAttachFullText]）と blob 化に使う。
 * 伏せ字ルール適用時は [fullText] も伏せ字後の短い文言になるため、全文が漏れることはない。
 */
class PreparedForwardedNotification(
    val payload: NotificationPayload,
    val fullText: String,
)

/**
 * 捕捉した通知をフィルタ判定し、転送対象なら [NotificationPayload] を組み立てる（§7）。
 * 転送しない場合は null を返す。詳細は [prepareForwardedNotification] に委ねる。
 */
fun buildNotificationPayload(
    input: NotificationInput,
    mode: FilterMode,
    rules: List<FilterRule>,
    deviceId: String,
    now: Long,
    otpSenderPackages: List<String> = emptyList(),
    isImplicitlySystemPackage: (String) -> Boolean = { it in DEFAULT_SYSTEM_PACKAGES },
    idGen: () -> String = ::newPayloadId,
    log: Logger = Logger.withTag("Forward"),
    deviceName: String? = null,
): NotificationPayload? = prepareForwardedNotification(
    input, mode, rules, deviceId, now, otpSenderPackages, isImplicitlySystemPackage, idGen, log, deviceName,
)?.payload

/**
 * 捕捉した通知をフィルタ判定し、転送対象なら組んだ [NotificationPayload] と本文全文を返す（§7、§4.3）。
 * 転送しない場合は null を返す。OTP 検出はタイトルと本文の連結に対して行う。
 * タイトル・本文は上限長で切り詰める（切り詰めは debug ログに残し、本文自体は出さない）。
 * [isImplicitlySystemPackage] で denylist の暗黙除外を判定する。送信側はランチャー有無を加味した
 * 動的判定を注入し、既定は静的な [DEFAULT_SYSTEM_PACKAGES] のみを見る。
 */
fun prepareForwardedNotification(
    input: NotificationInput,
    mode: FilterMode,
    rules: List<FilterRule>,
    deviceId: String,
    now: Long,
    otpSenderPackages: List<String> = emptyList(),
    isImplicitlySystemPackage: (String) -> Boolean = { it in DEFAULT_SYSTEM_PACKAGES },
    idGen: () -> String = ::newPayloadId,
    log: Logger = Logger.withTag("Forward"),
    deviceName: String? = null,
): PreparedForwardedNotification? {
    val isOtp = isOtpNotification("${input.title} ${input.text}", input.packageName, otpSenderPackages)
    val decision = decideFilter(input.packageName, input.priority, isOtp, mode, rules, isImplicitlySystemPackage)
    if (!decision.forward) return null

    val rawTitle = if (decision.redact) REDACTED_PLACEHOLDER else input.title
    val rawText = if (decision.redact) REDACTED_PLACEHOLDER else input.text
    val title = truncateToUtf8Bytes(rawTitle, MAX_FORWARDED_TITLE_BYTES)
    val text = truncateToUtf8Bytes(rawText, MAX_FORWARDED_TEXT_BYTES)
    if (title.length < rawTitle.length || text.length < rawText.length) {
        log.d { "forwarded notification truncated for ${input.packageName}" }
    }
    val actions = input.actions.take(MAX_FORWARDED_ACTIONS).map { truncateToUtf8Bytes(it, MAX_ACTION_LABEL_BYTES) }
    val actionDetails = input.actionDetails.take(MAX_FORWARDED_ACTIONS)

    return PreparedForwardedNotification(
        payload = NotificationPayload(
            id = idGen(),
            from = deviceId,
            to = BROADCAST_TARGET,
            sentAtEpochMillis = now,
            packageName = input.packageName,
            appName = input.appName,
            title = title,
            text = text,
            notificationKey = input.notificationKey,
            actions = actions,
            actionDetails = actionDetails,
            postedAtEpochMillis = input.postedAtEpochMillis,
            expiresAtEpochMillis = if (isOtp) now + OTP_TTL_MILLIS else null,
            priority = decision.priority,
            fromName = deviceName,
            visibility = input.visibility,
        ),
        fullText = rawText,
    )
}

/**
 * 直接受信した SMS を転送用の [SmsPayload] に組み立てる（§3.1）。
 * 優先度は既定で HIGH、失効は受信時刻 + 10 分とする。本文は上限長で切り詰める。
 */
fun buildSmsPayload(
    senderNumber: String,
    text: String,
    deviceId: String,
    now: Long,
    senderName: String? = null,
    idGen: () -> String = ::newPayloadId,
    deviceName: String? = null,
): SmsPayload = SmsPayload(
    id = idGen(),
    from = deviceId,
    to = BROADCAST_TARGET,
    sentAtEpochMillis = now,
    senderNumber = senderNumber,
    senderName = senderName,
    text = truncateToUtf8Bytes(text, MAX_FORWARDED_TEXT_BYTES),
    postedAtEpochMillis = now,
    expiresAtEpochMillis = now + SMS_TTL_MILLIS,
    priority = Priority.HIGH,
    fromName = deviceName,
)

/**
 * 転送済みの SMS に、後から判明した元通知の key を載せた改版を作る（§3.1）。
 * 受信側は id と revision の対で差し替えるため、本文や添付は転送時のものをそのまま引き継ぐ。
 */
fun withSmsNotificationKey(payload: SmsPayload, notificationKey: String): SmsPayload =
    payload.copy(notificationKey = notificationKey, revision = payload.revision + 1)

/**
 * 本文が [FULL_TEXT_PREVIEW_BYTES] を超え、かつセンシティブでないとき、全文を blob 添付すべきか判定する（§4.3）。
 * [attachFullTextWhenTruncated] が false（トグル OFF）なら常に false（従来どおり単純切り詰め）。
 * 本文が [MAX_FULL_TEXT_ATTACHMENT_BYTES] を超える場合も添付しない（切り詰めプレビューのみで送る）。
 * センシティブ判定は既存の履歴伏せ字判定（[payloadForPersistence]）を再利用し、履歴で本文を伏せる対象
 * （[persistSensitiveHistory] が false のときの SMS・OTP 通知）は全文 blob も作らない（伏せ字の意図に反しないため）。
 */
fun shouldAttachFullText(
    payload: Payload,
    fullText: String,
    attachFullTextWhenTruncated: Boolean,
    persistSensitiveHistory: Boolean,
): Boolean {
    if (!attachFullTextWhenTruncated) return false
    val byteLength = utf8ByteLength(fullText)
    if (byteLength <= FULL_TEXT_PREVIEW_BYTES) return false
    if (byteLength > MAX_FULL_TEXT_ATTACHMENT_BYTES) return false
    return !isSensitiveForAttachment(payload, persistSensitiveHistory)
}

/**
 * この payload の本文が履歴で伏せ字対象か（＝blob 添付を作らない対象か）を既存判定で見る。
 * [payloadForPersistence] は伏せる必要が無ければ同一インスタンスを返すため、その同一性で判定する。
 */
private fun isSensitiveForAttachment(payload: Payload, persistSensitiveHistory: Boolean): Boolean =
    payloadForPersistence(payload, keepSensitive = persistSensitiveHistory) !== payload

/**
 * 通知に元の画像・送信者アイコンを添付すべきか判定する（§4.3.1）。
 * [attachNotificationImages] が false（トグル OFF）なら付けない。履歴で本文を伏せる対象
 * （OTP 通知・SMS）も、全文添付と同じ理由で付けない。
 */
fun shouldAttachNotificationImage(
    payload: Payload,
    attachNotificationImages: Boolean,
    persistSensitiveHistory: Boolean,
): Boolean = attachNotificationImages && !isSensitiveForAttachment(payload, persistSensitiveHistory)

/**
 * アップロード済みの本文画像 [image]・送信者アイコン [senderIcon] を足した改版の通知を組む（§4.3.1）。
 * どちらも無ければ null を返し、呼び出し側は改版を送らない。両方あっても改版は 1 回だけ進め、
 * 受信側が 1 度の差し替えで両方を受け取れるようにする。
 */
fun withImageAttachments(
    payload: NotificationPayload,
    image: AttachmentRef? = null,
    senderIcon: AttachmentRef? = null,
): NotificationPayload? {
    if (image == null && senderIcon == null) return null
    return payload.copy(
        attachments = image?.let { payload.attachments + it } ?: payload.attachments,
        senderIcon = senderIcon ?: payload.senderIcon,
        revision = payload.revision + 1,
    )
}

/**
 * 本文全文が長い通知に全文添付を付ける（§4.3）。添付不要（トグル OFF・センシティブ・プレビュー予算内）なら
 * [payload] をそのまま返す。添付するときはインライン本文をプレビュー予算で切り詰め、全文を [uploadFullText] で
 * blob 化した [AttachmentRef] を末尾に加える。
 */
suspend fun attachFullTextIfNeeded(
    payload: NotificationPayload,
    fullText: String,
    attachFullTextWhenTruncated: Boolean,
    persistSensitiveHistory: Boolean,
    uploadFullText: suspend (text: String) -> AttachmentRef,
): NotificationPayload {
    if (!shouldAttachFullText(payload, fullText, attachFullTextWhenTruncated, persistSensitiveHistory)) return payload
    val ref = uploadFullText(fullText)
    return payload.copy(
        text = truncateToUtf8Bytes(fullText, FULL_TEXT_PREVIEW_BYTES),
        attachments = payload.attachments + ref,
    )
}

/**
 * 本文全文が長い SMS に全文添付を付ける（§4.3）。挙動は [NotificationPayload] 版と同じで、
 * 添付不要なら [payload] をそのまま返す。
 */
suspend fun attachFullTextIfNeeded(
    payload: SmsPayload,
    fullText: String,
    attachFullTextWhenTruncated: Boolean,
    persistSensitiveHistory: Boolean,
    uploadFullText: suspend (text: String) -> AttachmentRef,
): SmsPayload {
    if (!shouldAttachFullText(payload, fullText, attachFullTextWhenTruncated, persistSensitiveHistory)) return payload
    val ref = uploadFullText(fullText)
    return payload.copy(
        text = truncateToUtf8Bytes(fullText, FULL_TEXT_PREVIEW_BYTES),
        attachments = payload.attachments + ref,
    )
}
