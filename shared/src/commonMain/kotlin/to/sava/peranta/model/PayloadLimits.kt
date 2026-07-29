package to.sava.peranta.model

/**
 * ワイヤ形式（§4）の上限。**送信側の切り詰めと受信側の再検証が同じ値を見る**ための単一の置き場で、
 * 表示の都合で決まる値（サムネイルの寸法、トーストの行数など）はここへ置かない。
 *
 * 受信側はこれらを「送信元が守っていること」として信頼せず、表示・永続化の前に自分で当て直す
 * （`to.sava.peranta.receive.normalizeReceivedPayload`）。当て方は**拒否ではなく切り詰め**である。
 * 配布済みの旧バージョンはここへ後から入った上限を知らないため、超過を拒否にすると
 * その端末の通知が受信側で黙って消える。
 */

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

/** 転送するアクションの個数上限。通知 UI の実用上限に余裕を持たせた値。 */
const val MAX_FORWARDED_ACTIONS: Int = 5

/** アクション名 1 個あたりの UTF-8 バイト予算。 */
const val MAX_ACTION_LABEL_BYTES: Int = 100

/** 返信本文の UTF-8 バイト予算。転送本文と同じ封筒に収める。 */
const val MAX_REPLY_TEXT_BYTES: Int = MAX_FORWARDED_TEXT_BYTES

/** ファイル転送のキャプションの UTF-8 バイト予算。 */
const val MAX_CAPTION_BYTES: Int = MAX_FORWARDED_TEXT_BYTES

/** 端末間メッセージ本文の UTF-8 バイト予算。 */
const val MAX_MESSAGE_TEXT_BYTES: Int = MAX_FORWARDED_TEXT_BYTES

/**
 * 発信元まわりの短いラベル（アプリ名・端末名・SMS の送信者名／番号）の UTF-8 バイト予算。
 * 表示上は 1 行に収まる長さで足り、長大な値は発信元表示（§3.2）を押し流す手掛かりになる。
 */
const val MAX_SOURCE_LABEL_BYTES: Int = 100

/** 表示に載せる本文の行数上限。改行だけを詰めた本文が吹き出しを占有しないようにする。 */
const val MAX_DISPLAY_LINES: Int = 20

/** 切り詰め時に末尾へ付ける省略記号。 */
private const val TRUNCATION_ELLIPSIS: String = "…"

/** [value] の UTF-8 バイト長を返す。 */
fun utf8ByteLength(value: String): Int = value.encodeToByteArray().size

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
fun truncateToUtf8Bytes(value: String, maxBytes: Int): String {
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
