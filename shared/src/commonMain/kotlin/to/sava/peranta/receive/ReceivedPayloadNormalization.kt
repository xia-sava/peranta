package to.sava.peranta.receive

import to.sava.peranta.blob.MAX_ATTACHMENT_FILENAME_BYTES
import to.sava.peranta.blob.MAX_FULL_TEXT_ATTACHMENT_BYTES
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.FilePayload
import to.sava.peranta.model.MAX_ACTION_LABEL_BYTES
import to.sava.peranta.model.MAX_CAPTION_BYTES
import to.sava.peranta.model.MAX_DISPLAY_LINES
import to.sava.peranta.model.MAX_FORWARDED_ACTIONS
import to.sava.peranta.model.MAX_FORWARDED_TEXT_BYTES
import to.sava.peranta.model.MAX_FORWARDED_TITLE_BYTES
import to.sava.peranta.model.MAX_MESSAGE_TEXT_BYTES
import to.sava.peranta.model.MAX_SOURCE_LABEL_BYTES
import to.sava.peranta.model.MessagePayload
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.PresencePayload
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.model.truncateToUtf8Bytes

/**
 * 表示から取り除く双方向制御文字（Unicode の Bidi_Control）のコードポイント範囲。
 * 論理順と表示順を食い違わせられるため、同形異字と組み合わせると発信元やファイル名を偽装できる。
 * 文字そのものは不可視で、ソースに直接置くと読み手が存在を確認できないためコードポイントで書く。
 */
private val BIDI_CONTROL_RANGES: List<IntRange> = listOf(
    0x061C..0x061C, // ARABIC LETTER MARK
    0x200E..0x200F, // LEFT-TO-RIGHT MARK / RIGHT-TO-LEFT MARK
    0x202A..0x202E, // EMBEDDING / OVERRIDE / POP DIRECTIONAL FORMATTING
    0x2066..0x2069, // ISOLATE / POP DIRECTIONAL ISOLATE
)

/** 1 行に畳むときに空白へ置き換える改行文字。 */
private val LINE_BREAKS: Set<Char> = setOf('\n', '\r')

/** 連続した空白を 1 つへ畳むためのパターン（1 行表示の値にだけ当てる）。 */
private val BLANK_RUN = Regex(" {2,}")

/**
 * 復号済みの受信 payload を、表示・永続化の前に**ワイヤ形式（§4）の約束へ収め直す**。
 *
 * 上限は送信側が切り詰めた結果としてしか存在せず、受信側は同じ実装が送ってくることを信頼できない。
 * ここが唯一の関門で、この後ろの表示層・永続層は payload が約束を満たしていることを前提にできる。
 *
 * **超過は拒否せず切り詰める。** 配布済みの旧バージョンは後から入った上限を知らないため、
 * 落とすとその端末の通知が受信側で黙って消える（送信側は成功したつもりのまま気づけない）。
 *
 * 上限の再検証と併せて、表示へ渡る文字列の正規化（[normalizeDisplayText] / [normalizeDisplayLine]）も
 * ここで通す。目的は違う（前者は資源の上限、後者は表示のなりすまし対策）が、**どちらも「復号は通ったが
 * 素性を信じる理由が無い文字列」に当てる処理**で、通す場所を表示面ごとに分けると表示面を足したときに
 * 片方だけ抜ける。表示面ごとではなく受信の 1 箇所で当てる。
 *
 * 識別子（`id` / `from` / `to` / `packageName` / `notificationKey` / 添付の `blobId`・`url`）は
 * 表示ではなく突き合わせに使う値なので触らない。表示に出ない payload（command）もそのまま返す。
 * presence は端末名だけが端末一覧（§3.5）へ出るため、そこだけ正規化する。
 */
fun normalizeReceivedPayload(payload: Payload): Payload = when (payload) {
    is NotificationPayload -> payload.copy(
        appName = normalizeDisplayLine(payload.appName, MAX_SOURCE_LABEL_BYTES),
        title = normalizeDisplayLine(payload.title, MAX_FORWARDED_TITLE_BYTES),
        text = normalizeDisplayText(payload.text, MAX_FORWARDED_TEXT_BYTES),
        actions = payload.actions
            .take(MAX_FORWARDED_ACTIONS)
            .map { normalizeDisplayLine(it, MAX_ACTION_LABEL_BYTES) },
        actionDetails = payload.actionDetails.take(minOf(payload.actions.size, MAX_FORWARDED_ACTIONS)),
        attachments = payload.attachments.map(::normalizedRef),
        senderIcon = payload.senderIcon?.let(::normalizedRef),
        fromName = payload.fromName?.let { normalizeDisplayLine(it, MAX_SOURCE_LABEL_BYTES) },
    )

    is SmsPayload -> payload.copy(
        senderNumber = normalizeDisplayLine(payload.senderNumber, MAX_SOURCE_LABEL_BYTES),
        senderName = payload.senderName?.let { normalizeDisplayLine(it, MAX_SOURCE_LABEL_BYTES) },
        text = normalizeDisplayText(payload.text, MAX_FORWARDED_TEXT_BYTES),
        attachments = payload.attachments.map(::normalizedRef),
        fromName = payload.fromName?.let { normalizeDisplayLine(it, MAX_SOURCE_LABEL_BYTES) },
    )

    is FilePayload -> payload.copy(
        caption = payload.caption?.let { normalizeDisplayText(it, MAX_CAPTION_BYTES) },
        attachments = payload.attachments.map(::normalizedRef),
        fromName = payload.fromName?.let { normalizeDisplayLine(it, MAX_SOURCE_LABEL_BYTES) },
    )

    is MessagePayload -> payload.copy(
        text = normalizeDisplayText(payload.text, MAX_MESSAGE_TEXT_BYTES),
        fromName = payload.fromName?.let { normalizeDisplayLine(it, MAX_SOURCE_LABEL_BYTES) },
    )

    is PresencePayload -> payload.copy(
        deviceName = normalizeDisplayLine(payload.deviceName, MAX_SOURCE_LABEL_BYTES),
    )

    else -> payload
}

/** 添付参照のうち表示に出る値（ファイル名）だけを直す。所在と復号パラメータは突き合わせに使うため触らない。 */
private fun normalizedRef(ref: AttachmentRef): AttachmentRef =
    ref.copy(fileName = normalizeDisplayLine(ref.fileName, MAX_ATTACHMENT_FILENAME_BYTES))

/**
 * 1 行で表示する値（タイトル・アプリ名・端末名・送信者名・アクション名・ファイル名）を正規化する。
 * 制御文字と双方向制御文字を取り除き、改行を空白へ畳んで 1 行にし、[maxBytes] で切り詰める。
 * これらの値に改行の意味は無く、通せば発信元表示（§3.2）を押し流して別の通知に見せかけられる。
 */
fun normalizeDisplayLine(value: String, maxBytes: Int): String =
    value.map { if (it in LINE_BREAKS) ' ' else it }
        .filterNot { it.isDroppedFromDisplay() }
        .joinToString("")
        .replace(BLANK_RUN, " ")
        .trim()
        .let { truncateToUtf8Bytes(it, maxBytes) }

/**
 * 複数行で表示する本文（通知本文・SMS 本文・キャプション・メッセージ本文）を正規化する。
 * 改行は表示上の意味を持つ（複数行の通知）ため残し、制御文字と双方向制御文字だけを取り除く。
 * 空行の連続は 1 行へ畳み、前後の空行を落とす。空行を詰めて発信元と本文の境界を曖昧にし、
 * 別アプリからの通知に見せかける手口（§3.3 のトーストは本文を 4 行まで出す）を封じる。
 * 行数は [maxLines]、バイト数は [maxBytes] で頭打ちにする。
 */
fun normalizeDisplayText(value: String, maxBytes: Int, maxLines: Int = MAX_DISPLAY_LINES): String =
    value.filterNot { it !in LINE_BREAKS && it.isDroppedFromDisplay() }
        .lines()
        .map { it.trimEnd() }
        .dropWhile { it.isEmpty() }
        .dropLastWhile { it.isEmpty() }
        .fold(mutableListOf<String>()) { kept, line ->
            kept.apply { if (line.isNotEmpty() || lastOrNull()?.isNotEmpty() == true) add(line) }
        }
        .take(maxLines)
        .joinToString("\n")
        .let { truncateToUtf8Bytes(it, maxBytes) }

/**
 * 全文添付（kind=TEXT、§4.3）から取り出した本文を表示前に正規化する。
 * 素性を信じる理由が無いのはインライン本文と同じなので同じ処理を当てるが、全文は
 * 「切り詰めた本文の代わりに全部を読む」ためのものなので行数では頭打ちにしない。
 */
fun normalizeFullText(value: String): String =
    normalizeDisplayText(value, MAX_FULL_TEXT_ATTACHMENT_BYTES.toInt(), maxLines = Int.MAX_VALUE)

/** 表示から取り除く文字か。C0 / C1 制御文字と双方向制御文字が対象。 */
private fun Char.isDroppedFromDisplay(): Boolean =
    isISOControl() || BIDI_CONTROL_RANGES.any { code in it }
