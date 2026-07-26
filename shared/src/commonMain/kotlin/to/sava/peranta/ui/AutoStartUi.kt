package to.sava.peranta.ui

/**
 * ログオン時自動起動の設定口（§3.3・§10.2）。自動起動に対応するプラットフォーム（現状 Desktop）
 * だけが渡し、渡されない画面ではこの設定を出さない。
 *
 * [editable] が false のときも項目自体は消さず、操作できない状態で見せる。設定が見当たらないと
 * 「無くなった」と受け取られるため、存在は示したうえで理由を [unavailableInDevBuild] で注記する。
 */
class AutoStartUi(
    /** 現在の登録状態を読む。画面を開いたときの初期表示に使う。 */
    val isEnabled: () -> Boolean,
    /** 登録・解除を行えるか。配布物でない開発実行では false。 */
    val editable: Boolean,
    /** 開発実行のため操作できない状態か。注記を出すかの判断に使う。 */
    val unavailableInDevBuild: Boolean = false,
    /** 登録（true）・解除（false）を行う。 */
    val onChange: (Boolean) -> Unit,
)
