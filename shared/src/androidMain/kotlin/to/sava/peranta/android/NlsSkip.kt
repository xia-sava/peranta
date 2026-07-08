package to.sava.peranta.android

/** アプリ自身のパッケージ名（自分の通知を転送してループするのを防ぐ）。 */
const val SELF_PACKAGE = "to.sava.peranta"

/**
 * 捕捉した通知を NLS 側で落とすべきかを純粋に判定する。
 * - 自分自身の通知は常に落とす（ループ防止）。
 * - 進行中通知（[isOngoing]）とグループ要約（[isGroupSummary]）は既定で落とす。
 * - 既定 SMS アプリからの通知で、直接受信済み SMS と重複する場合は落とす（§3.1）。
 */
fun shouldSkipNotification(
    packageName: String,
    defaultSmsPackage: String?,
    isSmsDuplicate: Boolean,
    isOngoing: Boolean,
    isGroupSummary: Boolean,
): Boolean {
    if (packageName == SELF_PACKAGE) return true
    if (isOngoing) return true
    if (isGroupSummary) return true
    if (packageName == defaultSmsPackage && isSmsDuplicate) return true
    return false
}
