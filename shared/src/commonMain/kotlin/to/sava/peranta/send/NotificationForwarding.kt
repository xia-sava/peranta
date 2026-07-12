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
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.Priority
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.model.newPayloadId

/** OTP 通知に付ける失効までの猶予（§4）。 */
const val OTP_TTL_MILLIS: Long = 5 * 60 * 1000L

/** SMS 転送に付ける失効までの猶予（§3.1）。 */
const val SMS_TTL_MILLIS: Long = 10 * 60 * 1000L

/** 伏せ字適用時にタイトル・本文へ入れる文字列。 */
const val REDACTED_PLACEHOLDER: String = "（内容は伏せられています）"

/**
 * 転送で載せるタイトルの UTF-8 バイト予算。
 * ntfy の message 上限（既定 4096 bytes）に封筒・base64 膨張（4/3）と各種メタの
 * オーバーヘッドを見込み、タイトル・本文の平文合計が上限に収まるよう配分する。
 */
const val MAX_FORWARDED_TITLE_BYTES: Int = 300

/**
 * 転送で載せる本文の UTF-8 バイト予算。
 * base64 膨張（4/3）と封筒・JSON フィールドのオーバーヘッドを見込み、
 * タイトルと合わせた平文ペイロードが暗号化後に 4096 bytes へ収まるよう抑える。
 */
const val MAX_FORWARDED_TEXT_BYTES: Int = 2000

/**
 * 全文添付を作るときにインラインへ残すプレビュー本文の UTF-8 バイト予算（§4.3）。
 * これを超える本文は全文を暗号化 blob として別送し、インラインはこの予算で切り詰めたプレビューにする。
 */
const val FULL_TEXT_PREVIEW_BYTES: Int = 512

/** 切り詰め時に末尾へ付ける省略記号。 */
private const val TRUNCATION_ELLIPSIS: String = "…"

/** [value] の UTF-8 バイト長を返す。 */
private fun utf8ByteLength(value: String): Int = value.encodeToByteArray().size

/** コードポイント [codePoint] を UTF-8 で表したときのバイト数を返す。 */
private fun utf8ByteWidth(codePoint: Int): Int = when {
    codePoint < 0x80 -> 1
    codePoint < 0x800 -> 2
    codePoint < 0x10000 -> 3
    else -> 4
}

/** サロゲートペア [high]/[low] を 1 つのコードポイント値へ合成する。 */
private fun combineSurrogates(high: Char, low: Char): Int =
    0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)

/**
 * [value] を UTF-8 で [maxBytes] バイト以内に収める。超過時はコードポイント境界で切り、
 * 末尾を省略記号にする。サロゲートペア（絵文字等）を分断しない。
 */
fun truncateForForwarding(value: String, maxBytes: Int): String {
    if (utf8ByteLength(value) <= maxBytes) return value
    val budget = maxBytes - utf8ByteLength(TRUNCATION_ELLIPSIS)
    val builder = StringBuilder()
    var usedBytes = 0
    var index = 0
    while (index < value.length) {
        val current = value[index]
        val isSurrogatePair = current.isHighSurrogate() &&
            index + 1 < value.length &&
            value[index + 1].isLowSurrogate()
        val codePoint = if (isSurrogatePair) combineSurrogates(current, value[index + 1]) else current.code
        val charWidth = if (isSurrogatePair) 2 else 1
        val byteWidth = utf8ByteWidth(codePoint)
        if (usedBytes + byteWidth > budget) break
        repeat(charWidth) { builder.append(value[index + it]) }
        usedBytes += byteWidth
        index += charWidth
    }
    return builder.append(TRUNCATION_ELLIPSIS).toString()
}

/** 捕捉した通知から取り出した素の値。sbn 抽出とフィルタ判定を分離するための入力。 */
data class NotificationInput(
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val notificationKey: String,
    val actions: List<String> = emptyList(),
    val postedAtEpochMillis: Long,
    val priority: Priority = Priority.NORMAL,
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
): NotificationPayload? = prepareForwardedNotification(
    input, mode, rules, deviceId, now, otpSenderPackages, isImplicitlySystemPackage, idGen, log,
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
): PreparedForwardedNotification? {
    val isOtp = isOtpNotification("${input.title} ${input.text}", input.packageName, otpSenderPackages)
    val decision = decideFilter(input.packageName, input.priority, isOtp, mode, rules, isImplicitlySystemPackage)
    if (!decision.forward) return null

    val rawTitle = if (decision.redact) REDACTED_PLACEHOLDER else input.title
    val rawText = if (decision.redact) REDACTED_PLACEHOLDER else input.text
    val title = truncateForForwarding(rawTitle, MAX_FORWARDED_TITLE_BYTES)
    val text = truncateForForwarding(rawText, MAX_FORWARDED_TEXT_BYTES)
    if (title.length < rawTitle.length || text.length < rawText.length) {
        log.d { "forwarded notification truncated for ${input.packageName}" }
    }

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
            actions = input.actions,
            postedAtEpochMillis = input.postedAtEpochMillis,
            expiresAtEpochMillis = if (isOtp) now + OTP_TTL_MILLIS else null,
            priority = decision.priority,
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
): SmsPayload = SmsPayload(
    id = idGen(),
    from = deviceId,
    to = BROADCAST_TARGET,
    sentAtEpochMillis = now,
    senderNumber = senderNumber,
    senderName = senderName,
    text = truncateForForwarding(text, MAX_FORWARDED_TEXT_BYTES),
    postedAtEpochMillis = now,
    expiresAtEpochMillis = now + SMS_TTL_MILLIS,
    priority = Priority.HIGH,
)

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
    return !isSensitiveForFullText(payload, persistSensitiveHistory)
}

/**
 * この payload の本文が履歴で伏せ字対象か（＝全文 blob を作らない対象か）を既存判定で見る。
 * [payloadForPersistence] は伏せる必要が無ければ同一インスタンスを返すため、その同一性で判定する。
 */
private fun isSensitiveForFullText(payload: Payload, persistSensitiveHistory: Boolean): Boolean =
    payloadForPersistence(payload, keepSensitive = persistSensitiveHistory) !== payload

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
        text = truncateForForwarding(fullText, FULL_TEXT_PREVIEW_BYTES),
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
        text = truncateForForwarding(fullText, FULL_TEXT_PREVIEW_BYTES),
        attachments = payload.attachments + ref,
    )
}
