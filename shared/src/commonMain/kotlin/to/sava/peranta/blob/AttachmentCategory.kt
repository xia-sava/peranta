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

/** ファイル名から拡張子（小文字、ドット無し）を取り出す。拡張子が無ければ空。 */
private fun extensionOf(fileName: String): String {
    val dot = fileName.lastIndexOf('.')
    if (dot <= 0 || dot == fileName.length - 1) return ""
    return fileName.substring(dot + 1).lowercase()
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
 * mimeType を第一に、判別できないときはファイル名の拡張子を補助にして表示種別を決める（§4.3）。
 * mimeType のトップレベル型（image・video・audio）を優先し、application 系や不明な型は拡張子で
 * 文書・動画・音声・画像を拾い、いずれにも当てはまらなければ [AttachmentCategory.OTHER] とする。
 */
fun attachmentCategoryFor(mimeType: String, fileName: String): AttachmentCategory {
    val topLevel = mimeType.substringBefore('/').lowercase()
    when (topLevel) {
        "image" -> return AttachmentCategory.IMAGE
        "video" -> return AttachmentCategory.VIDEO
        "audio" -> return AttachmentCategory.AUDIO
        "text" -> return AttachmentCategory.DOCUMENT
    }
    if (mimeType == "application/pdf") return AttachmentCategory.DOCUMENT
    return when (extensionOf(fileName)) {
        in IMAGE_EXTENSIONS -> AttachmentCategory.IMAGE
        in VIDEO_EXTENSIONS -> AttachmentCategory.VIDEO
        in AUDIO_EXTENSIONS -> AttachmentCategory.AUDIO
        in DOCUMENT_EXTENSIONS -> AttachmentCategory.DOCUMENT
        else -> AttachmentCategory.OTHER
    }
}
