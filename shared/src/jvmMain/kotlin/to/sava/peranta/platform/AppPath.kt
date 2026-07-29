package to.sava.peranta.platform

import co.touchlab.kermit.Logger
import java.io.File
import java.io.IOException

/**
 * 配布物の実行ファイルのパス（jpackage の `jpackage.app-path`）を扱う（§12）。
 *
 * この値は JVM のシステムプロパティ由来で、起動時の環境から差し替えられる。更新後の再起動と
 * 自動起動の登録（§3.3）はこのパスを実行対象にするため、実在する実行ファイルで、かつ自分が
 * 動いているランタイム（`java.home`）を配下に持つ場所にあるものだけを使う。
 */
object AppPath {

    private val log = Logger.withTag("AppPath")

    /** 検証を通った実行ファイルのパス。開発実行や検証に落ちた値では null。 */
    val verified: String? by lazy {
        System.getProperty("jpackage.app-path")
            ?.takeIf { isTrusted(it, System.getProperty("java.home")) }
    }

    /**
     * [appPath] を実行対象として使ってよいかを判定する。
     * 実在する実行ファイルで、その置き場の配下に [runtimeHome] があることを条件とする。
     */
    fun isTrusted(appPath: String?, runtimeHome: String?): Boolean {
        if (appPath.isNullOrBlank() || runtimeHome.isNullOrBlank()) return false
        return try {
            val app = File(appPath).canonicalFile
            val installDir = app.parentFile ?: return false
            app.isFile && File(runtimeHome).canonicalFile.toPath().startsWith(installDir.toPath())
        } catch (error: IOException) {
            log.w(error) { "app path rejected: cannot resolve $appPath" }
            false
        }
    }
}
