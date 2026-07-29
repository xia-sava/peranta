package to.sava.peranta.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppPathTest {

    /** 配布物の構成（インストール先に実行ファイルとランタイムが並ぶ）を作り、その 2 つを返す。 */
    private fun installation(): Pair<File, File> {
        val installDir = Files.createTempDirectory("peranta-install").toFile().apply { deleteOnExit() }
        val launcher = File(installDir, "Peranta.exe").apply {
            writeText("launcher")
            deleteOnExit()
        }
        val runtime = File(installDir, "runtime").apply {
            mkdirs()
            deleteOnExit()
        }
        return launcher to runtime
    }

    /** 実在する実行ファイルで、その置き場の配下にランタイムがあれば実行対象として使える。 */
    @Test
    fun acceptsLauncherHoldingTheRuntime() {
        val (launcher, runtime) = installation()

        assertTrue(AppPath.isTrusted(launcher.absolutePath, runtime.absolutePath))
    }

    /** 自分が動いているランタイムと無関係な場所の実行ファイルは使わない。 */
    @Test
    fun rejectsLauncherOutsideOwnInstallation() {
        val (elsewhere, _) = installation()
        val (_, runtime) = installation()

        assertFalse(AppPath.isTrusted(elsewhere.absolutePath, runtime.absolutePath))
    }

    /** 実在しないパス・実行ファイルでないパス・空の値は使わない。 */
    @Test
    fun rejectsAbsentOrBlankPaths() {
        val (launcher, runtime) = installation()

        assertFalse(AppPath.isTrusted(File(launcher.parentFile, "Absent.exe").absolutePath, runtime.absolutePath))
        assertFalse(AppPath.isTrusted(launcher.parentFile.absolutePath, runtime.absolutePath))
        assertFalse(AppPath.isTrusted(null, runtime.absolutePath))
        assertFalse(AppPath.isTrusted("   ", runtime.absolutePath))
        assertFalse(AppPath.isTrusted(launcher.absolutePath, null))
    }
}
