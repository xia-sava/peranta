package to.sava.peranta.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 適用の結果を書き残す先。 */
private const val RESULT_LOG_PATH = """C:\Users\tester\AppData\Roaming\Peranta\logs\update-apply.log"""

class ApplyUpdateScriptTest {

    private fun script(
        msiPath: String = """C:\tmp\peranta-update.msi""",
        launcherPath: String = """C:\Apps\Peranta.exe""",
        resultLogPath: String = RESULT_LOG_PATH,
        waitPids: List<Long> = listOf(1L),
        waitSeconds: Int = 60,
    ): String = applyUpdateScript(msiPath, launcherPath, resultLogPath, waitPids, waitSeconds)

    /** 待ち対象の PID を列挙し、その終了を待ってから msiexec を無人モードで走らせる。 */
    @Test
    fun waitsForOwnProcessesThenInstallsSilently() {
        val script = script(waitPids = listOf(100L, 200L), waitSeconds = 30)

        assertTrue(script.contains("@(100,200)"), script)
        assertTrue(script.contains("Wait-Process -Id \$id -Timeout 30"), script)
        assertTrue(script.contains("'/i', 'C:\\tmp\\peranta-update.msi', '/qn'"), script)
    }

    /** 起動する実行ファイルは %SystemRoot% から組んだ絶対パスで指す（PATH の解決に委ねない）。 */
    @Test
    fun resolvesMsiexecUnderSystemRoot() {
        val script = script()

        assertTrue(script.contains("""Join-Path ${'$'}env:SystemRoot 'System32\msiexec.exe'"""), script)
        assertFalse(script.contains("Start-Process msiexec"), script)
    }

    /** 失敗を黙らせるのは終了済みプロセスを待つ行だけで、スクリプト全体では失敗を伝える。 */
    @Test
    fun silencesErrorsOnlyWhileWaitingForProcesses() {
        val script = script()

        assertTrue(script.contains("Wait-Process -Id \$id -Timeout 60 -ErrorAction SilentlyContinue"), script)
        assertFalse(script.contains("\$ErrorActionPreference = 'SilentlyContinue'"), script)
    }

    /** msiexec の終了コードを書き残し、成功しなければランチャーを起動せずに終える。 */
    @Test
    fun recordsInstallerExitCodeAndStopsOnFailure() {
        val script = script()

        assertTrue(script.contains("""${'$'}log = '$RESULT_LOG_PATH'"""), script)
        assertTrue(script.contains("if (\$code -ne 0) {"), script)
        assertTrue(script.contains("msiexec failed with exit code \$code"), script)
        assertTrue(script.contains("exit \$code"), script)
    }

    /** 適用後に配布物のランチャーを起動する（更新前に動いていた状態へ戻す）。 */
    @Test
    fun restartsLauncherAfterInstall() {
        val script = script(msiPath = """C:\tmp\u.msi""")

        assertTrue(script.trimEnd().endsWith("""Start-Process 'C:\Apps\Peranta.exe'"""), script)
    }

    /** 引用符を含むパスでも PowerShell のリテラルを閉じてしまわない。 */
    @Test
    fun escapesQuotesInPaths() {
        val script = script(
            msiPath = """C:\it's\u.msi""",
            launcherPath = """C:\it's\Peranta.exe""",
            resultLogPath = """C:\it's\logs\update-apply.log""",
        )

        assertTrue(script.contains("""'C:\it''s\u.msi'"""), script)
        assertTrue(script.contains("""'C:\it''s\Peranta.exe'"""), script)
        assertTrue(script.contains("""'C:\it''s\logs\update-apply.log'"""), script)
    }

    /** 待ち対象が無くても壊れたスクリプトにならない（空の配列として展開する）。 */
    @Test
    fun handlesEmptyPidList() {
        val script = script(waitPids = emptyList())

        assertTrue(script.contains("@()"), script)
        assertFalse(script.contains("@(,"), script)
    }
}
