package to.sava.peranta.ui.setup

/**
 * 受信のセットアップ手順の番号・タイトル・説明文を単一所有する。
 * 動作チェックからの誘導文もこの定数から組み立て、手順名・番号の重複による齟齬を防ぐ。
 */
object ReceiveSetupSteps {

    const val NTFY_INSTALLED_ID: String = "ntfy-installed"
    const val SERVER_CONFIG_ID: String = "up-server-config"
    const val UNIFIED_PUSH_ID: String = "unifiedpush"
    const val NTFY_BATTERY_ID: String = "ntfy-battery"
    const val SELF_TEST_ID: String = "up-self-test"

    /** 手順の表示順。番号はこの並びの位置で決まる。 */
    val orderedIds: List<String> = listOf(
        NTFY_INSTALLED_ID,
        SERVER_CONFIG_ID,
        UNIFIED_PUSH_ID,
        NTFY_BATTERY_ID,
        SELF_TEST_ID,
    )

    /** 手順番号（1 始まり）。未知の id はエラーにする。 */
    fun numberOf(id: String): Int {
        val index = orderedIds.indexOf(id)
        require(index >= 0) { "未知の受信セットアップ手順: $id" }
        return index + 1
    }

    /** 手順タイトル。未知の id はエラーにする。 */
    fun titleOf(id: String): String =
        when (id) {
            NTFY_INSTALLED_ID -> "ntfy アプリの導入"
            SERVER_CONFIG_ID -> "ntfy にサーバを設定"
            UNIFIED_PUSH_ID -> "UnifiedPush の登録"
            NTFY_BATTERY_ID -> "ntfy を省電力から除外"
            SELF_TEST_ID -> "受信テスト"
            else -> throw IllegalArgumentException("未知の受信セットアップ手順: $id")
        }

    /** 手順の説明文（手段の単一の置き場）。未知の id はエラーにする。 */
    fun descriptionOf(id: String): String =
        when (id) {
            NTFY_INSTALLED_ID ->
                "UnifiedPush の配信には ntfy アプリが必要です。導入して既定に設定してください。"
            SERVER_CONFIG_ID ->
                "ntfy アプリの 設定 →「デフォルトのサーバー」と、「カスタムヘッダー」の サービスURL の" +
                    " 2 箇所に同じサーバーURL を設定し、あわせてヘッダ名・ヘッダ値を登録します。" +
                    "「ユーザーの管理」でこのサーバーのユーザーを登録済みなら、カスタムヘッダーの登録は不要です。"
            UNIFIED_PUSH_ID ->
                "受信エンドポイントの払い出しを受けるには UnifiedPush へ登録してください。"
            NTFY_BATTERY_ID ->
                "常時受信のため ntfy を最適化から除外してください。開いた一覧から「ntfy」を探して除外します。"
            SELF_TEST_ID ->
                "テスト通知を自分宛にサーバ経由で送り、実際に受信できるかを確認します。"
            else -> throw IllegalArgumentException("未知の受信セットアップ手順: $id")
        }

    /** 手順 1 つを指す「手順N」の表記。未知の id はエラーにする。 */
    fun labelOf(id: String): String = "手順${numberOf(id)}"

    /** [fromId] から [toId] までの連続した手順を指す「手順N〜M」の表記。未知の id はエラーにする。 */
    fun rangeLabelOf(fromId: String, toId: String): String = "手順${numberOf(fromId)}〜${numberOf(toId)}"

    /** 「受信のセットアップ 手順N「タイトル」で直せます」形式の誘導文。 */
    fun guidanceTo(id: String): String =
        "受信のセットアップ ${labelOf(id)}「${titleOf(id)}」で直せます"
}
