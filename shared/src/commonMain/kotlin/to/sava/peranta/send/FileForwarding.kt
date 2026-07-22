package to.sava.peranta.send

import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.Envelope
import to.sava.peranta.model.FilePayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.Priority
import to.sava.peranta.model.encodeEnvelope
import to.sava.peranta.model.encodePayload
import to.sava.peranta.model.newPayloadId

/** 共有された画像・ファイルのキャプションに載せる UTF-8 バイト予算（本文と同じ配分に倣う）。 */
const val MAX_CAPTION_BYTES: Int = MAX_FORWARDED_TEXT_BYTES

/**
 * UnifiedPush が配送元に保証する最小メッセージサイズ（bytes、§4.3）。
 * Desktop の WebSocket 購読はサーバ設定の上限まで受けられるが、Android タブレットは UnifiedPush 受信のため
 * これを超える Envelope は経路上で欠落し得る。複数添付を分割する実質上限に使う。
 */
const val UNIFIED_PUSH_ENVELOPE_BUDGET: Int = 4096

/** AES-GCM の認証タグ長（bytes）。封緘後サイズの見積りに使う。 */
private const val GCM_TAG_BYTES: Int = 16

/** AES-GCM の nonce 長（bytes）。封緘後サイズの見積りに使う。 */
private const val NONCE_BYTES: Int = 12

/** サイズ見積り用の id プレースホルダ（UUID と同じ 36 文字長で、実 id と同じ Envelope サイズになる）。 */
private const val SIZING_ID_PLACEHOLDER: String = "00000000-0000-0000-0000-000000000000"

/** [byteCount] バイトを base64 化したときの文字数（`4 * ceil(n / 3)`）。 */
private fun base64Length(byteCount: Int): Int = ((byteCount + 2) / 3) * 4

/**
 * [payload] を [keyId] で封緘したときの Envelope の UTF-8 バイト長を、実際に暗号化せず決定的に見積る（§4.3）。
 * 暗号文長 = 平文長 + GCM タグ、base64 は 4/3 に膨張し、封筒 JSON は nonce・keyId・キー名の固定分を加える。
 * base64 は JSON エスケープされない安全文字なので、同じ長さのダミー文字列を実シリアライザに通して測る。
 */
fun forwardedEnvelopeSize(payload: Payload, keyId: String): Int {
    val plaintextBytes = encodePayload(payload).encodeToByteArray().size
    val ciphertextChars = base64Length(plaintextBytes + GCM_TAG_BYTES)
    val nonceChars = base64Length(NONCE_BYTES)
    val envelope = Envelope(
        keyId = keyId,
        nonce = "A".repeat(nonceChars),
        ciphertext = "A".repeat(ciphertextChars),
    )
    return encodeEnvelope(envelope).encodeToByteArray().size
}

/**
 * アップロード済みの [attachments] を [FilePayload] に組み立てる（§4.3）。
 * [caption] は切り詰めたうえで載せ、空なら null にする。宛先は全端末（`to: "*"`）とする。
 */
fun buildFilePayload(
    deviceId: String,
    attachments: List<AttachmentRef>,
    now: Long,
    caption: String? = null,
    priority: Priority = Priority.NORMAL,
    idGen: () -> String = ::newPayloadId,
    deviceName: String? = null,
): FilePayload {
    require(attachments.isNotEmpty()) { "FilePayload requires at least one attachment" }
    val trimmedCaption = caption
        ?.let { truncateForForwarding(it, MAX_CAPTION_BYTES) }
        ?.takeIf { it.isNotBlank() }
    return FilePayload(
        id = idGen(),
        from = deviceId,
        to = BROADCAST_TARGET,
        sentAtEpochMillis = now,
        caption = trimmedCaption,
        attachments = attachments,
        postedAtEpochMillis = now,
        priority = priority,
        fromName = deviceName,
    )
}

/**
 * アップロード済みの [attachments] を、封緘後の Envelope が [maxEnvelopeBytes] に収まる範囲で
 * 1 つ以上の [FilePayload] に分割して組み立てる（§4.3）。
 * UnifiedPush の実質上限を超える Envelope は受信タブレットで欠落し得るため、添付を貪欲に詰めて分割する。
 * これにより「サイレントに一部の添付が消える」ことを避ける。
 * [caption] は全ペイロードに載せると重複で膨らむため、先頭ペイロードにのみ載せる（切り詰めたうえで）。
 * 添付 1 件だけで上限を超える病的なケースでも、その添付は単独ペイロードに載せて送る（落とさない）。
 */
fun buildFilePayloads(
    deviceId: String,
    attachments: List<AttachmentRef>,
    keyId: String,
    now: Long,
    caption: String? = null,
    priority: Priority = Priority.NORMAL,
    maxEnvelopeBytes: Int = UNIFIED_PUSH_ENVELOPE_BUDGET,
    idGen: () -> String = ::newPayloadId,
    deviceName: String? = null,
): List<FilePayload> {
    require(attachments.isNotEmpty()) { "FilePayload requires at least one attachment" }
    val cappedCaption = caption
        ?.let { truncateForForwarding(it, MAX_CAPTION_BYTES) }
        ?.takeIf { it.isNotBlank() }
    // キャプションは JSON エスケープで最大約 2 倍に膨らむため、封緘後サイズで先頭バッチが予算に収まるよう追加で切り詰める。
    val budgetedCaption = cappedCaption?.let {
        fitCaptionWithinBudget(deviceId, attachments.first(), keyId, now, it, priority, maxEnvelopeBytes, deviceName)
    }
    return packAttachments(deviceId, attachments, keyId, now, budgetedCaption, priority, maxEnvelopeBytes, deviceName)
        .mapIndexed { index, batch ->
            FilePayload(
                id = idGen(),
                from = deviceId,
                to = BROADCAST_TARGET,
                sentAtEpochMillis = now,
                caption = budgetedCaption.takeIf { index == 0 },
                attachments = batch,
                postedAtEpochMillis = now,
                priority = priority,
                fromName = deviceName,
            )
        }
}

/**
 * 先頭バッチ（キャプション + 添付 1 件）の封緘後 Envelope が [maxEnvelopeBytes] を超える場合に、
 * キャプションを追加で切り詰めて予算内へ収める（§4.3）。キャプションの JSON エスケープ膨張で
 * 分割不能な第 1 バッチが生じるのを防ぐ。添付は落とさず、まずキャプションを削る方針とする。
 * 添付単独でも超過する病的なケースではキャプションを外す（添付は単独バッチで送る）。
 */
private fun fitCaptionWithinBudget(
    deviceId: String,
    firstAttachment: AttachmentRef,
    keyId: String,
    now: Long,
    caption: String,
    priority: Priority,
    maxEnvelopeBytes: Int,
    deviceName: String?,
): String? {
    fun envelopeSizeWith(candidate: String?): Int =
        forwardedEnvelopeSize(
            sizingPayload(deviceId, listOf(firstAttachment), now, candidate, priority, deviceName),
            keyId,
        )
    if (envelopeSizeWith(caption) <= maxEnvelopeBytes) return caption
    if (envelopeSizeWith(null) > maxEnvelopeBytes) return null
    // キャプションのバイト予算を狭めるほど Envelope は単調に縮むため、収まる最大長を二分探索する。
    var low = 0
    var high = caption.encodeToByteArray().size
    var best: String? = null
    while (low <= high) {
        val mid = (low + high) / 2
        val candidate = truncateForForwarding(caption, mid).takeIf { it.isNotBlank() }
        if (envelopeSizeWith(candidate) <= maxEnvelopeBytes) {
            best = candidate
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return best
}

/**
 * 添付を、封緘後 Envelope が [maxEnvelopeBytes] に収まる複数バッチへ貪欲に振り分ける（§4.3）。
 * キャプションは先頭バッチにのみ載る前提でサイズを見積る。バッチが空のまま 1 件で超過する場合は、
 * そのまま単独バッチに載せて次へ進む（無限ループにも、サイレントな欠落にもしない）。
 */
private fun packAttachments(
    deviceId: String,
    attachments: List<AttachmentRef>,
    keyId: String,
    now: Long,
    caption: String?,
    priority: Priority,
    maxEnvelopeBytes: Int,
    deviceName: String?,
): List<List<AttachmentRef>> {
    val batches = mutableListOf<List<AttachmentRef>>()
    var current = mutableListOf<AttachmentRef>()
    attachments.forEach { attachment ->
        val captionForBatch = caption.takeIf { batches.isEmpty() }
        val candidate = current + attachment
        val fits = forwardedEnvelopeSize(
            sizingPayload(deviceId, candidate, now, captionForBatch, priority, deviceName),
            keyId,
        ) <= maxEnvelopeBytes
        if (fits || current.isEmpty()) {
            current = candidate.toMutableList()
        } else {
            batches.add(current)
            current = mutableListOf(attachment)
        }
    }
    if (current.isNotEmpty()) batches.add(current)
    return batches
}

/** サイズ見積り専用の [FilePayload]。id は固定長プレースホルダで、実配送物と同じ Envelope サイズになる。 */
private fun sizingPayload(
    deviceId: String,
    attachments: List<AttachmentRef>,
    now: Long,
    caption: String?,
    priority: Priority,
    deviceName: String?,
): FilePayload = FilePayload(
    id = SIZING_ID_PLACEHOLDER,
    from = deviceId,
    to = BROADCAST_TARGET,
    sentAtEpochMillis = now,
    caption = caption,
    attachments = attachments,
    postedAtEpochMillis = now,
    priority = priority,
    fromName = deviceName,
)
