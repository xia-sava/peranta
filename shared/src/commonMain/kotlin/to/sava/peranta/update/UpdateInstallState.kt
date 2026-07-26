package to.sava.peranta.update

/** 更新の適用（ダウンロードから起動まで）の進み具合（§12）。 */
sealed interface UpdateInstallState {

    /** 配布物をダウンロードしている。[totalBytes] は全体長が判らなければ 0。 */
    data class Downloading(val receivedBytes: Long, val totalBytes: Long) : UpdateInstallState {

        /** 0.0〜1.0 の進み具合。全体長が判らなければ null。 */
        val fraction: Float?
            get() = if (totalBytes > 0) (receivedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
    }

    /** ダウンロードした配布物を照合している。 */
    data object Verifying : UpdateInstallState

    /**
     * 照合まで済み、適用の確認待ち。適用するとアプリが終了して更新後に起動し直すため、
     * 常駐が途切れることを承知してもらってから進める。
     */
    data object ReadyToApply : UpdateInstallState

    /** インストーラへ引き渡した。この後アプリ自身は終了する。 */
    data object Launching : UpdateInstallState

    /** 適用できなかった。理由を握り潰さず保持する。 */
    data class Failed(val reason: String) : UpdateInstallState
}
