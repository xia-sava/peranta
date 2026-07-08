package to.sava.peranta.update

/** 更新確認の結果。 */
sealed interface UpdateStatus {

    /** 自分の versionCode が最新（配布物が自分以下）。 */
    data object UpToDate : UpdateStatus

    /** より新しい配布物がある。 */
    data class Available(val versionName: String, val url: String) : UpdateStatus

    /** 取得・解析の失敗、または該当プラットフォームキーの欠落。理由を握り潰さず保持する。 */
    data class Failed(val reason: String) : UpdateStatus
}
