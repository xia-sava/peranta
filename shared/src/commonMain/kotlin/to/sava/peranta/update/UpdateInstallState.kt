package to.sava.peranta.update

/** 更新の適用（ダウンロードから起動まで）の進み具合（§12）。 */
sealed interface UpdateInstallState {

    /** 配布物をダウンロードしている。 */
    data object Downloading : UpdateInstallState

    /** ダウンロードした配布物を照合している。 */
    data object Verifying : UpdateInstallState

    /** インストーラへ引き渡した。この後アプリ自身は終了する。 */
    data object Launching : UpdateInstallState

    /** 適用できなかった。理由を握り潰さず保持する。 */
    data class Failed(val reason: String) : UpdateInstallState
}
