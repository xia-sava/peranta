package to.sava.peranta.toast

import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SnoreToast プロセス起動の統合テスト。トーストは出さず、appID 付き -close に存在しない id を渡して
 * プロセスが安全に起動・終了し、既定 appID のショートカットを副作用として作らないことを確認する。
 *
 * PERANTA_TOAST_IT=1 のときだけ実行する（未設定なら assumeTrue で SKIP）。
 * exe パスは PERANTA_TOAST_EXE で上書きでき、既定は desktopApp の生成物を見る。
 */
class SnoreToastProcessIT {

    private val defaultExe =
        File("../desktopApp/build/generated/snoretoast/snoretoast.exe")

    /** Start Menu\Programs 直下のショートカット名の集合（ディレクトリが無ければ空）。 */
    private fun startMenuShortcuts(): Set<String> {
        val programs = File(System.getenv("APPDATA").orEmpty(), "Microsoft\\Windows\\Start Menu\\Programs")
        return programs.listFiles { file -> file.name.endsWith(".lnk") }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()
    }

    /** ゲート有効時、appID 付き -close はプロセスが終了し、ショートカットを新規作成しない。 */
    @Test
    fun closeWithAppIdTerminatesWithoutShortcutSideEffect() {
        assumeTrue("PERANTA_TOAST_IT!=1 のためスキップ", System.getenv("PERANTA_TOAST_IT") == "1")

        val exe = System.getenv("PERANTA_TOAST_EXE")?.let(::File) ?: defaultExe
        assertTrue(exe.exists(), "snoretoast.exe not found at ${exe.absolutePath}; run :desktopApp:buildSnoreToast")

        val shortcutsBefore = startMenuShortcuts()

        val args = SnoreToastCommand.closeArgs(exe.absolutePath, "peranta-it-nonexistent")
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val terminated = process.waitFor(15, TimeUnit.SECONDS)
        if (!terminated) {
            process.destroyForcibly()
        }
        assertTrue(terminated, "snoretoast -close did not terminate within timeout")

        val shortcutsAfter = startMenuShortcuts()
        assertEquals(
            shortcutsBefore,
            shortcutsAfter,
            "snoretoast -close created shortcut side effect: ${shortcutsAfter - shortcutsBefore}",
        )
    }
}
