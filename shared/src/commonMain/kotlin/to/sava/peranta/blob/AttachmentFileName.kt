package to.sava.peranta.blob

/**
 * 添付ファイル名の UTF-8 バイト予算（§4.3、持ち越し事項）。
 * 転送エンベロープ（暗号文 base64 4/3 膨張 + JSON メタ）が UnifiedPush の実質上限に収まるよう、
 * 長大なファイル名を切り詰める上限。拡張子は残すため、この予算は base 部と拡張子の合計に効く。
 */
const val MAX_ATTACHMENT_FILENAME_BYTES: Int = 96

/** 拡張子とみなす末尾ドット区切りの最大長（バイト）。これを超える場合は拡張子扱いしない。 */
private const val MAX_EXTENSION_BYTES: Int = 16

/** ファイル名を組み立てられないときのフォールバック。 */
private const val FALLBACK_FILENAME: String = "attachment"

/** Windows がパスに使えない文字。ローカル保存で [java.nio.file.InvalidPathException] を避けるため置換する。 */
private const val WINDOWS_FORBIDDEN_CHARS: String = "<>:\"|?*"

/** 禁止文字の置換先。 */
private const val FORBIDDEN_CHAR_REPLACEMENT: Char = '_'

/** Windows の予約デバイス名。大文字小文字・拡張子有無を問わず一致したら避ける。 */
private val WINDOWS_RESERVED_NAMES: Set<String> = buildSet {
    add("CON")
    add("PRN")
    add("AUX")
    add("NUL")
    (1..9).forEach { add("COM$it") }
    (1..9).forEach { add("LPT$it") }
}

/** 予約名と一致した場合に付ける接尾辞（拡張子の前へ差し込む）。 */
private const val RESERVED_NAME_SUFFIX: String = "_file"

/** [value] の UTF-8 バイト長。 */
private fun utf8ByteLength(value: String): Int = value.encodeToByteArray().size

/** コードポイント [codePoint] を UTF-8 で表したときのバイト数。 */
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
 * [value] を UTF-8 で [maxBytes] バイト以内に収める（コードポイント境界で切り、サロゲートペアを分断しない）。
 * 省略記号は付けない（ファイル名用途のため）。
 */
private fun truncateToBytes(value: String, maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    if (utf8ByteLength(value) <= maxBytes) return value
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
        if (usedBytes + byteWidth > maxBytes) break
        repeat(charWidth) { builder.append(value[index + it]) }
        usedBytes += byteWidth
        index += charWidth
    }
    return builder.toString()
}

/** [name] を最後のドットで base 部と拡張子（ドット込み）に分ける。拡張子が無ければ第 2 要素は空。 */
private fun splitExtension(name: String): Pair<String, String> {
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.length - 1) return name to ""
    val extension = name.substring(dot)
    if (utf8ByteLength(extension) > MAX_EXTENSION_BYTES) return name to ""
    return name.substring(0, dot) to extension
}

/**
 * [name] を UTF-8 [maxBytes] バイト以内に切り詰める。拡張子（`.jpg` 等）は残し、base 部だけを削る（§4.3）。
 * 拡張子だけで予算を超える病的なケースでは、拡張子も含めて全体を切り詰める。
 */
fun truncateAttachmentFileName(name: String, maxBytes: Int = MAX_ATTACHMENT_FILENAME_BYTES): String {
    if (utf8ByteLength(name) <= maxBytes) return name
    val (base, extension) = splitExtension(name)
    val extensionBytes = utf8ByteLength(extension)
    if (extensionBytes >= maxBytes) return truncateToBytes(name, maxBytes)
    val truncatedBase = truncateToBytes(base, maxBytes - extensionBytes)
    return truncatedBase + extension
}

/**
 * 受信した添付ファイル名をローカル保存前に無害化する（§4.3、パストラバーサル対策）。
 * パスセパレータ（`/` `\`）で最後のセグメントだけを取り、制御文字を除去し、
 * Windows が使えない文字（[WINDOWS_FORBIDDEN_CHARS]）を [FORBIDDEN_CHAR_REPLACEMENT] へ置換し、
 * 末尾のドット・空白（Windows が切り落とす）と先頭ドット（`..` 等の隠し/相対参照）を落とす。
 * Windows 予約デバイス名に一致したら [RESERVED_NAME_SUFFIX] を付けて回避する。
 * 空になったら [fallback] を使う。バイト長は呼び出し側が [truncateAttachmentFileName] で別途上限する。
 */
fun sanitizeAttachmentFileName(name: String, fallback: String = FALLBACK_FILENAME): String {
    val lastSegment = name.substringAfterLast('/').substringAfterLast('\\')
    val withoutControls = lastSegment.filterNot { it.isISOControl() }
    val withoutForbidden = withoutControls.map {
        if (it in WINDOWS_FORBIDDEN_CHARS) FORBIDDEN_CHAR_REPLACEMENT else it
    }.joinToString("")
    val withoutTrailing = withoutForbidden.trim().trimEnd('.', ' ')
    val withoutLeadingDots = withoutTrailing.trimStart('.')
    val safe = withoutLeadingDots.ifBlank { fallback }
    return avoidWindowsReservedName(safe)
}

/** [name] が Windows 予約デバイス名（拡張子含む/含まない）に一致したら、拡張子の前へ接尾辞を挟んで回避する。 */
private fun avoidWindowsReservedName(name: String): String {
    val dot = name.indexOf('.')
    val stem = if (dot >= 0) name.substring(0, dot) else name
    if (stem.uppercase() !in WINDOWS_RESERVED_NAMES) return name
    val extension = if (dot >= 0) name.substring(dot) else ""
    return stem + RESERVED_NAME_SUFFIX + extension
}

/** 送信・受信双方で使う、無害化と長さ制限を合わせた添付ファイル名の正規化。 */
fun normalizeAttachmentFileName(name: String): String =
    truncateAttachmentFileName(sanitizeAttachmentFileName(name))
