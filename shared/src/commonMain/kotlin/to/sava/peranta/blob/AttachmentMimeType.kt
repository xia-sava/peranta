package to.sava.peranta.blob

/** 種別が判らないファイルの MIME（§4.3）。 */
const val DEFAULT_ATTACHMENT_MIME_TYPE: String = "application/octet-stream"

/** MIME の type か subtype に入るワイルドカード。これを含む MIME は具体的な種別を語らない。 */
private const val MIME_WILDCARD: String = "*"

/**
 * [mimeType] からパラメータ（`; charset=...`）と前後の空白を落として小文字にそろえ、それが中身の種別を語るなら
 * その形で返す。語らなければ null を返す。
 * 空のもの、`type/subtype` の形でないもの、途中に空白や制御文字を含むもの、type か subtype が [MIME_WILDCARD] のもの、
 * [DEFAULT_ATTACHMENT_MIME_TYPE] は語らないとみなす。
 */
fun specificMimeTypeOrNull(mimeType: String?): String? =
    mimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        ?.takeIf { essence ->
            val parts = essence.split('/')
            parts.size == 2 &&
                parts.none { it.isEmpty() || it == MIME_WILDCARD } &&
                essence.none { it.isWhitespace() || it.isISOControl() } &&
                essence != DEFAULT_ATTACHMENT_MIME_TYPE
        }

/**
 * [mimeType] が中身の種別を語るならその形（[specificMimeTypeOrNull]）を返す。語らなければ、[fileName] の拡張子
 * （小文字・ドットなし）を [mimeTypeForExtension] で引いて補い、それでも判らなければ
 * [DEFAULT_ATTACHMENT_MIME_TYPE] を返す（§4.3）。送るファイルの種別を決めるときと、復号済みの添付を開くアプリを
 * OS に探させるとき（§4.3.2）に使う。ワイルドカードを含む MIME は、あらゆる種別を扱うアプリに一致して
 * 開く先が広がるため、OS へそのまま渡さない。
 */
fun resolveAttachmentMimeType(
    mimeType: String?,
    fileName: String,
    mimeTypeForExtension: (extension: String) -> String?,
): String =
    specificMimeTypeOrNull(mimeType)
        ?: attachmentExtensionOf(fileName)
            .takeIf { it.isNotEmpty() }
            ?.let { extension -> specificMimeTypeOrNull(mimeTypeForExtension(extension)) }
        ?: DEFAULT_ATTACHMENT_MIME_TYPE
