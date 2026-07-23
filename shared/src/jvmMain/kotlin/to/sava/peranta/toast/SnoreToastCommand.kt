package to.sava.peranta.toast

/** SnoreToast の起動引数の組み立てと exit code の解釈（純粋ロジック）。 */
object SnoreToastCommand {

    /** Peranta の AppUserModelID。トースト・ショートカットで一貫して用いる。 */
    const val APP_USER_MODEL_ID: String = "Peranta"

    /** 「消す」ボタンのラベル。押下は exit code 4 で判別する。 */
    const val DISMISS_BUTTON_LABEL: String = "消す"

    /** 「開く」ボタンのラベル。本文から URL が抽出できたときのみ追加する（§3.3）。 */
    const val OPEN_BUTTON_LABEL: String = "開く"

    /** ボタン押下（複数ボタン構成含む）を示す SnoreToast の exit code。 */
    private const val BUTTON_PRESSED_EXIT_CODE: Int = 4

    /** -id / -close に渡す通知 id を英数・ハイフン・アンダースコアに正規化する。 */
    fun sanitizeId(id: String): String =
        id.map { ch -> if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '-' }
            .joinToString(separator = "")
            .ifEmpty { "toast" }

    /**
     * トースト表示の起動引数を組み立てる。先頭は [exePath]。[ReceivedNotificationToast.openUrl] が
     * あれば「開く」「消す」の 2 ボタン構成にし、無ければ従来どおり「消す」の 1 ボタン構成にする。
     */
    fun showArgs(exePath: String, item: ReceivedNotificationToast): List<String> {
        val buttons = if (item.openUrl != null) {
            "$OPEN_BUTTON_LABEL;$DISMISS_BUTTON_LABEL"
        } else {
            DISMISS_BUTTON_LABEL
        }
        return listOf(
            exePath,
            "-persistent",
            "-appID", APP_USER_MODEL_ID,
            "-t", item.title,
            "-m", item.body,
            "-id", sanitizeId(item.id),
            "-b", buttons,
        )
    }

    /** 表示済みトースト取り下げの起動引数を組み立てる。appID を明示し、表示時と同じ通知グループを対象にする。 */
    fun closeArgs(exePath: String, id: String): List<String> =
        listOf(exePath, "-appID", APP_USER_MODEL_ID, "-close", sanitizeId(id))

    /** ショートカット登録（-install）の起動引数を組み立てる。 */
    fun installArgs(exePath: String, shortcutName: String): List<String> =
        listOf(exePath, "-install", shortcutName, exePath, APP_USER_MODEL_ID)

    /**
     * SnoreToast の exit code を [ToastResult] に対応づける（§3.3）。
     * 「消す」の 1 ボタン構成専用で、exit code 4 は無条件に [ToastResult.ButtonDismiss] とする。
     */
    fun resultFromExitCode(code: Int): ToastResult =
        when (code) {
            0 -> ToastResult.Clicked
            2 -> ToastResult.Dismissed
            3 -> ToastResult.TimedOut
            BUTTON_PRESSED_EXIT_CODE -> ToastResult.ButtonDismiss
            else -> ToastResult.Failed
        }

    /**
     * 「開く」「消す」の 2 ボタン構成向けの判別（§3.3）。ボタン押下（exit code 4）のときのみ
     * [stdout] の 1 行目（トリム済み）をボタン名と比較する。不明なラベルは [ToastResult.ButtonDismiss]
     * に寄せず [ToastResult.Failed] とする（誤って「開く」扱いにしないための安全側）。
     * ボタン押下以外の exit code は [resultFromExitCode] と同じ判別に委ねる。
     */
    fun resultFrom(exitCode: Int, stdout: String): ToastResult =
        if (exitCode == BUTTON_PRESSED_EXIT_CODE) {
            when (stdout.lineSequence().firstOrNull()?.trim()) {
                OPEN_BUTTON_LABEL -> ToastResult.ButtonOpen
                DISMISS_BUTTON_LABEL -> ToastResult.ButtonDismiss
                else -> ToastResult.Failed
            }
        } else {
            resultFromExitCode(exitCode)
        }
}
