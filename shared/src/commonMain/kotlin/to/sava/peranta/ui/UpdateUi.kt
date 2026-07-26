package to.sava.peranta.ui

import to.sava.peranta.update.UpdateController
import to.sava.peranta.update.UpdateInstallState
import to.sava.peranta.update.UpdateStatus

/**
 * 「アプリの更新」セクションの配線（§12・§10.2）。確認から適用までの経路はプラットフォームで
 * 異なるため、画面はこの束を受け取って表示と操作の受け渡しだけを担う。
 *
 * [onApply] を渡すプラットフォーム（現状 Desktop）では、照合の完了後に適用の確認を挟む。
 * 適用するとアプリが終了して更新後に起動し直すため、常駐が途切れることを断ってから進める。
 */
class UpdateUi(
    /** 更新確認の実行状態と結果。 */
    val controller: UpdateController,
    /** 動作中の版。解決できない実行経路では null。 */
    val currentVersionName: String? = null,
    /** 適用の進み具合。未着手は null。 */
    val installState: UpdateInstallState? = null,
    /** 配布物のダウンロードと照合を始める。 */
    val onInstall: ((UpdateStatus.Available) -> Unit)? = null,
    /** 確認を経て適用へ進む。確認を挟まないプラットフォームでは null。 */
    val onApply: (() -> Unit)? = null,
    /** 適用の確認を取りやめ、ダウンロード済みの配布物を捨てる。 */
    val onCancelApply: (() -> Unit)? = null,
)
