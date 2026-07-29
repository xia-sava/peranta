package to.sava.peranta.blob

import to.sava.peranta.model.AttachmentKind

/**
 * 添付カードのアイコン表示に使う簡易な種類分け（§4.3）。
 * 画像かどうかだけを持つ [AttachmentKind] を、UI 表示向けにもう一段だけ細かく分類する。
 */
enum class AttachmentCategory {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    OTHER,
}

/**
 * ファイル名から拡張子（小文字、ドット無し）を取り出す。拡張子が無ければ空。
 * OS が実際に見るのは保存時に無害化されたファイル名なので、判定もその形に揃える
 * （末尾のドット・空白を落とした後の名前で決める、[normalizeAttachmentFileName]）。
 */
internal fun attachmentExtensionOf(fileName: String): String {
    val normalized = normalizeAttachmentFileName(fileName)
    val dot = normalized.lastIndexOf('.')
    if (dot <= 0 || dot == normalized.length - 1) return ""
    return normalized.substring(dot + 1).lowercase()
}

/** 文書とみなす拡張子。mimeType が曖昧（application オクテットストリーム等）なときの補助判定に使う。 */
private val DOCUMENT_EXTENSIONS: Set<String> = setOf(
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
    "txt", "csv", "md", "rtf", "odt", "ods", "odp",
)

/** 動画とみなす拡張子。 */
private val VIDEO_EXTENSIONS: Set<String> = setOf("mp4", "mkv", "mov", "avi", "webm", "m4v", "3gp")

/** 音声とみなす拡張子。 */
private val AUDIO_EXTENSIONS: Set<String> = setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "opus")

/** 画像とみなす拡張子。 */
private val IMAGE_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

/**
 * mimeType が image で始まれば [AttachmentKind.IMAGE]、それ以外は [AttachmentKind.FILE] とする（§4.3）。
 * 送信側の共有シート・受信側の表示で共通に使う。
 */
fun attachmentKindForMimeType(mimeType: String): AttachmentKind =
    if (mimeType.startsWith("image/")) AttachmentKind.IMAGE else AttachmentKind.FILE

/**
 * mimeType だけから表示種別を決める。トップレベル型（image・video・audio・text）と
 * `application/pdf` で判る場合だけ値を返し、`application/octet-stream` のような
 * 中身を語らない型では null を返す。
 */
fun attachmentCategoryForMimeType(mimeType: String): AttachmentCategory? {
    when (mimeType.substringBefore('/').lowercase()) {
        "image" -> return AttachmentCategory.IMAGE
        "video" -> return AttachmentCategory.VIDEO
        "audio" -> return AttachmentCategory.AUDIO
        "text" -> return AttachmentCategory.DOCUMENT
    }
    return if (mimeType.substringBefore(';').trim().lowercase() == "application/pdf") {
        AttachmentCategory.DOCUMENT
    } else {
        null
    }
}

/** ファイル名の拡張子だけから表示種別を決める。既知の拡張子でなければ null。 */
fun attachmentCategoryForExtension(fileName: String): AttachmentCategory? =
    when (attachmentExtensionOf(fileName)) {
        in IMAGE_EXTENSIONS -> AttachmentCategory.IMAGE
        in VIDEO_EXTENSIONS -> AttachmentCategory.VIDEO
        in AUDIO_EXTENSIONS -> AttachmentCategory.AUDIO
        in DOCUMENT_EXTENSIONS -> AttachmentCategory.DOCUMENT
        else -> null
    }

/**
 * mimeType を第一に、判別できないときはファイル名の拡張子を補助にして表示種別を決める（§4.3）。
 * どちらでも判らなければ [AttachmentCategory.OTHER] とする。
 */
fun attachmentCategoryFor(mimeType: String, fileName: String): AttachmentCategory =
    attachmentCategoryForMimeType(mimeType)
        ?: attachmentCategoryForExtension(fileName)
        ?: AttachmentCategory.OTHER
