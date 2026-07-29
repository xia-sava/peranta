package to.sava.peranta.blob

/**
 * 受信した添付を OS へ渡してよいかの判定（§4.3.2）。
 * Desktop の `Desktop.open`（ShellExecute）も Android の `ACTION_VIEW` も、渡した時点で
 * 何が起きるかを決めるのは OS 側であり、アプリからは取り消せない。
 */
enum class AttachmentOpenDecision {
    /** 表示するだけで実行につながらないと判っている種別。確認なしで渡す。 */
    OPEN,

    /** 種別を確かめられない。外部由来である旨の確認を経てから渡す。 */
    CONFIRM,

    /** 実行につながる種別、または名乗りと拡張子が食い違う。渡さず保存へ誘導する。 */
    REFUSE,
}

/**
 * 開くだけで実行につながる拡張子。Windows の ShellExecute と Android のインストーラが
 * これらを「開く」の延長で走らせるため、mimeType が何を名乗っていても OS へ渡さない。
 */
private val EXECUTABLE_EXTENSIONS: Set<String> = setOf(
    "exe", "com", "pif", "scr", "bat", "cmd", "lnk", "url", "hta", "sh",
    "js", "jse", "vbs", "vbe", "wsf", "wsh", "ps1", "psm1", "ps1xml",
    "msi", "msp", "msc", "reg", "inf", "cpl", "scf", "chm", "hlp",
    "jar", "apk", "dll", "sys", "gadget", "application", "appref-ms",
)

/**
 * 確認を挟まずに開ける拡張子。**表示するだけで実行につながらない**種別に限る。
 * 画像や PDF を見るたびに確認を出すと読み飛ばされ、確認そのものが働かなくなるため、
 * 日常的に届く種別はここへ入れて確認の対象から外す。
 * マクロを持てる旧形式（doc / xls / ppt）と書庫は入れない（[AttachmentOpenDecision.CONFIRM] になる）。
 */
private val OPENABLE_EXTENSIONS: Set<String> = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif",
    "pdf", "txt", "csv", "md", "log", "rtf",
    "docx", "xlsx", "pptx", "odt", "ods", "odp",
    "mp4", "mkv", "mov", "webm", "m4v", "3gp",
    "mp3", "wav", "flac", "aac", "ogg", "m4a", "opus",
)

/**
 * パッケージインストーラへ直行する mimeType。Peranta は自己更新のため
 * `REQUEST_INSTALL_PACKAGES` を持つので、この型は拡張子に関わらず渡さない。
 */
private const val ANDROID_PACKAGE_MIME: String = "application/vnd.android.package-archive"

/** mimeType からパラメータ（`; charset=...`）を落とし、比較できる形に揃える。 */
private fun normalizeMimeType(mimeType: String): String = mimeType.substringBefore(';').trim().lowercase()

/**
 * [mimeType] が名乗る種別と [fileName] の拡張子が指す種別が食い違うか（§4.3.2）。
 *
 * 両者は送信側が独立に決められるため、片方だけを見ると「画像を名乗る実行ファイル」が素通りする。
 * 双方が種別を語れるときだけ突き合わせ、どちらかが語らない（`application/octet-stream`・
 * 拡張子なし等）ときは食い違いとしない。
 */
fun mimeAndExtensionConflict(mimeType: String, fileName: String): Boolean {
    val fromMimeType = attachmentCategoryForMimeType(mimeType) ?: return false
    val fromExtension = attachmentCategoryForExtension(fileName) ?: return false
    return fromMimeType != fromExtension
}

/**
 * 復号済みの添付を OS の既定アプリへ渡してよいかを決める（§4.3.2）。
 *
 * 判定は Desktop と Android で共通にする。渡した先での挙動は OS ごとに違うが、
 * 「送信側が自由に決めた `mimeType` と `fileName` しか根拠が無い」という前提は同じで、
 * 実装を分けると片方だけ緩い状態が差分から見えなくなる。
 *
 * **拡張子を厳しく見る。** Windows の ShellExecute は拡張子で扱いを決めるため、
 * 実行につながる拡張子は mimeType の名乗りに関わらず拒否する。
 */
fun attachmentOpenDecision(mimeType: String, fileName: String): AttachmentOpenDecision {
    if (attachmentExtensionOf(fileName) in EXECUTABLE_EXTENSIONS) return AttachmentOpenDecision.REFUSE
    if (normalizeMimeType(mimeType) == ANDROID_PACKAGE_MIME) return AttachmentOpenDecision.REFUSE
    if (mimeAndExtensionConflict(mimeType, fileName)) return AttachmentOpenDecision.REFUSE
    return if (attachmentExtensionOf(fileName) in OPENABLE_EXTENSIONS) {
        AttachmentOpenDecision.OPEN
    } else {
        AttachmentOpenDecision.CONFIRM
    }
}
