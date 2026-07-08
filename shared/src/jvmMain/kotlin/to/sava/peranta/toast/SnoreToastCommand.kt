package to.sava.peranta.toast

/** SnoreToast の起動引数の組み立てと exit code の解釈（純粋ロジック）。 */
object SnoreToastCommand {

    /** Peranta の AppUserModelID。トースト・ショートカットで一貫して用いる。 */
    const val APP_USER_MODEL_ID: String = "Peranta"

    /** 「消す」ボタンのラベル。押下は exit code 4 で判別する。 */
    const val DISMISS_BUTTON_LABEL: String = "消す"

    /** -id / -close に渡す通知 id を英数・ハイフン・アンダースコアに正規化する。 */
    fun sanitizeId(id: String): String =
        id.map { ch -> if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '-' }
            .joinToString(separator = "")
            .ifEmpty { "toast" }

    /** トースト表示の起動引数を組み立てる。先頭は [exePath]。 */
    fun showArgs(exePath: String, item: ReceivedNotificationToast): List<String> =
        listOf(
            exePath,
            "-persistent",
            "-appID", APP_USER_MODEL_ID,
            "-t", item.title,
            "-m", item.body,
            "-id", sanitizeId(item.id),
            "-b", DISMISS_BUTTON_LABEL,
        )

    /** 表示済みトースト取り下げの起動引数を組み立てる。appID を明示し、表示時と同じ通知グループを対象にする。 */
    fun closeArgs(exePath: String, id: String): List<String> =
        listOf(exePath, "-appID", APP_USER_MODEL_ID, "-close", sanitizeId(id))

    /** ショートカット登録（-install）の起動引数を組み立てる。 */
    fun installArgs(exePath: String, shortcutName: String): List<String> =
        listOf(exePath, "-install", shortcutName, exePath, APP_USER_MODEL_ID)

    /** SnoreToast の exit code を [ToastResult] に対応づける（§3.3）。 */
    fun resultFromExitCode(code: Int): ToastResult =
        when (code) {
            0 -> ToastResult.Clicked
            2 -> ToastResult.Dismissed
            3 -> ToastResult.TimedOut
            4 -> ToastResult.ButtonDismiss
            else -> ToastResult.Failed
        }
}
