package to.sava.peranta.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplyUpdateScriptTest {

    /** 待ち対象の PID を列挙し、その終了を待ってから msiexec を無人モードで走らせる。 */
    @Test
    fun waitsForOwnProcessesThenInstallsSilently() {
        val script = applyUpdateScript(
            msiPath = """C:\tmp\peranta-update.msi""",
            launcherPath = """C:\Apps\Peranta.exe""",
            waitPids = listOf(100L, 200L),
            waitSeconds = 30,
        )

        assertTrue(script.contains("@(100,200)"), script)
        assertTrue(script.contains("Wait-Process -Id \$id -Timeout 30"), script)
        assertTrue(script.contains("'/i', 'C:\\tmp\\peranta-update.msi', '/qn'"), script)
    }

    /** 適用後に配布物のランチャーを起動する（更新前に動いていた状態へ戻す）。 */
    @Test
    fun restartsLauncherAfterInstall() {
        val script = applyUpdateScript(
            msiPath = """C:\tmp\u.msi""",
            launcherPath = """C:\Apps\Peranta.exe""",
            waitPids = listOf(1L),
        )

        assertTrue(script.trimEnd().endsWith("""Start-Process 'C:\Apps\Peranta.exe'"""), script)
    }

    /** 引用符を含むパスでも PowerShell のリテラルを閉じてしまわない。 */
    @Test
    fun escapesQuotesInPaths() {
        val script = applyUpdateScript(
            msiPath = """C:\it's\u.msi""",
            launcherPath = """C:\it's\Peranta.exe""",
            waitPids = listOf(1L),
        )

        assertTrue(script.contains("""'C:\it''s\u.msi'"""), script)
        assertTrue(script.contains("""'C:\it''s\Peranta.exe'"""), script)
    }

    /** 待ち対象が無くても壊れたスクリプトにならない（空の配列として展開する）。 */
    @Test
    fun handlesEmptyPidList() {
        val script = applyUpdateScript(
            msiPath = """C:\tmp\u.msi""",
            launcherPath = """C:\Apps\Peranta.exe""",
            waitPids = emptyList(),
        )

        assertTrue(script.contains("@()"), script)
        assertFalse(script.contains("@(,"), script)
    }
}
