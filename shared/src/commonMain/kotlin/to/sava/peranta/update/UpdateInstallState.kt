package to.sava.peranta.update

/**
 * 配布物を取得できなかったときの理由（§12）。https でない取得先を拒んだときと、
 * 取得した配布物を照合のために読めなかったときも含む。
 */
const val UPDATE_DOWNLOAD_FAILED_MESSAGE: String = "更新のダウンロードに失敗しました"

/** ダウンロードした配布物が照合に落ちたときの理由（§12）。 */
const val UPDATE_VERIFY_FAILED_MESSAGE: String = "ダウンロードした更新の照合に失敗しました"

/**
 * 照合を通って適用の確認を待っていた配布物が、適用の直前の再照合に落ちたときの理由（§12）。
 * 配布物を読めなかったときも含む。
 */
const val UPDATE_REVERIFY_FAILED_MESSAGE: String = "適用する更新の照合に失敗しました"

/** 照合を通った配布物をインストーラへ引き渡せなかったときの理由（§12）。 */
const val UPDATE_APPLY_FAILED_MESSAGE: String = "更新の適用に失敗しました"

/**
 * 適用が進行中か（多重に走らせないための判定）。照合まで済んで適用の確認を待つあいだと、
 * インストーラへ引き渡したあとも進行中とする。未着手（null）と失敗は進行中ではない。
 */
fun UpdateInstallState?.isInProgress(): Boolean = when (this) {
    is UpdateInstallState.Downloading,
    UpdateInstallState.Verifying,
    UpdateInstallState.ReadyToApply,
    UpdateInstallState.Launching,
    -> true

    is UpdateInstallState.Failed, null -> false
}

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
