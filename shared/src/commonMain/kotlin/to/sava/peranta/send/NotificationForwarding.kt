package to.sava.peranta.send

import co.touchlab.kermit.Logger
import to.sava.peranta.filter.FilterMode
import to.sava.peranta.filter.FilterRule
import to.sava.peranta.filter.decideFilter
import to.sava.peranta.filter.isOtpNotification
import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.NotificationPayload
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
 * 捕捉した通知をフィルタ判定し、転送対象なら [NotificationPayload] を組み立てる（§7）。
 * 転送しない場合は null を返す。OTP 検出はタイトルと本文の連結に対して行う。
 * タイトル・本文は上限長で切り詰める（切り詰めは debug ログに残し、本文自体は出さない）。
 */
fun buildNotificationPayload(
    input: NotificationInput,
    mode: FilterMode,
    rules: List<FilterRule>,
    deviceId: String,
    now: Long,
    otpSenderPackages: List<String> = emptyList(),
    idGen: () -> String = ::newPayloadId,
    log: Logger = Logger.withTag("Forward"),
): NotificationPayload? {
    val isOtp = isOtpNotification("${input.title} ${input.text}", input.packageName, otpSenderPackages)
    val decision = decideFilter(input.packageName, input.priority, isOtp, mode, rules)
    if (!decision.forward) return null

    val rawTitle = if (decision.redact) REDACTED_PLACEHOLDER else input.title
    val rawText = if (decision.redact) REDACTED_PLACEHOLDER else input.text
    val title = truncateForForwarding(rawTitle, MAX_FORWARDED_TITLE_BYTES)
    val text = truncateForForwarding(rawText, MAX_FORWARDED_TEXT_BYTES)
    if (title.length < rawTitle.length || text.length < rawText.length) {
        log.d { "forwarded notification truncated for ${input.packageName}" }
    }

    return NotificationPayload(
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
