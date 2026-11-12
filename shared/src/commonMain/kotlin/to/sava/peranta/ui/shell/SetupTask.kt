package to.sava.peranta.ui.shell

/**
 * 画面シェルの外にある設定のタスク（§10.0）。[Wizard] は設定・診断をページ列で案内するウィザード
 * （§10.7）、[PairingLanding] は未ペアリング端末がウィザードを閉じたあとに着地する、ウィザードへ戻る
 * 導線つきの取り込み待機画面（§10.3）。
 */
enum class SetupTask { Wizard, PairingLanding }

/**
 * 起動時に開く設定のタスク。ペアリング済み、または取り込み画面（[ShellDestination.PairingImport]）を
 * 開いていた（再生成後の復元など）なら開かず null、それ以外の未ペアリングはウィザードから始める。
 */
fun initialSetupTask(paired: Boolean, destination: ShellDestination): SetupTask? = when {
    paired -> null
    destination == ShellDestination.PairingImport -> null
    else -> SetupTask.Wizard
}

/** ウィザードを閉じたあとのタスク。ペアリング済みなら null（シェルへ戻る）、未ペアリングなら待機画面。 */
fun setupTaskAfterWizardClose(paired: Boolean): SetupTask? =
    if (paired) null else SetupTask.PairingLanding
