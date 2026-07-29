package to.sava.peranta.autostart

/** 自動起動（§3.3）の点検結果。 */
enum class AutoStartStatus {
    /** 開発実行など配布物でないため自動起動を扱えない（画面には出さない）。 */
    NOT_SUPPORTED,

    /** 現在の実行ファイルを指す自動起動が登録済み。 */
    ENABLED,

    /** 自動起動は未登録。 */
    DISABLED,
}

/**
 * ログオン時自動起動（§3.3）の登録・照会・冪等な再登録を担う。
 * [appPath] は配布物の実行ファイルパスで、レジストリへ書く実行対象になるため
 * [to.sava.peranta.platform.AppPath.verified] の検証を通った値を渡す。
 * 開発実行や検証に落ちた値では null になり、その場合は自動起動を扱わない
 * （開発時の java 起動コマンドや、差し替えられたパスを誤登録しない）。
 * 登録コマンドには [launchArgument] を付け、ログオン起動時はウィンドウを出さずトレイ常駐で開始させる。
 */
class AutoStartManager(
    private val registry: AutoStartRegistry,
    private val appPath: String?,
    private val launchArgument: String = MINIMIZED_ARGUMENT,
) {

    /** 配布物として自動起動を扱えるか（実行ファイルパスが判っているか）。 */
    val isSupported: Boolean
        get() = !appPath.isNullOrBlank()

    /** ログオン起動時に付与する起動コマンド。パスに空白を含み得るためクォートで囲む。 */
    fun expectedCommand(): String? =
        appPath?.let { "\"$it\" $launchArgument" }

    fun status(): AutoStartStatus = when {
        !isSupported -> AutoStartStatus.NOT_SUPPORTED
        isEnabled() -> AutoStartStatus.ENABLED
        else -> AutoStartStatus.DISABLED
    }

    /** 現在の実行ファイルを指す自動起動が登録済みか。 */
    fun isEnabled(): Boolean =
        expectedCommand()?.let { it == registry.currentCommand() } ?: false

    /** 自動起動を登録する。開発実行など非対応環境、または登録処理自体の失敗では false を返す。 */
    fun enable(): Boolean {
        val command = expectedCommand() ?: return false
        return registry.register(command)
    }

    fun disable() {
        if (!isSupported) return
        registry.unregister()
    }

    /**
     * 起動時に、登録済みコマンドが現在の実行ファイルパスと食い違っていれば書き直す（§3.3）。
     * バージョンアップ等でインストール先が変わった場合に、既存の自動起動登録を現行パスへ追随させる。
     * 未登録のときは何もしない（利用者が無効にした状態を尊重し、勝手に有効化しない）。
     */
    fun reconcile() {
        if (!isSupported) return
        val current = registry.currentCommand() ?: return
        val expected = expectedCommand() ?: return
        if (current != expected) {
            registry.register(expected)
        }
    }

    companion object {
        /** ログオン起動時にウィンドウを出さずトレイ常駐で開始させる起動引数。 */
        const val MINIMIZED_ARGUMENT = "--minimized"
    }
}
