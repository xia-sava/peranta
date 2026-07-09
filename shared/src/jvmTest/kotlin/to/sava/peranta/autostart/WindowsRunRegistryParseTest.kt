package to.sava.peranta.autostart

import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsRunRegistryParseTest {

    /** reg query の出力からクォート・空白・起動引数を含むデータ列を丸ごと取り出す。 */
    @Test
    fun parsesQuotedCommandWithSpaces() {
        val output = """

            HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run
                Peranta    REG_SZ    "C:\Program Files\Peranta\Peranta.exe" --minimized

        """.trimIndent()
        assertEquals(
            "\"C:\\Program Files\\Peranta\\Peranta.exe\" --minimized",
            WindowsRunRegistry.parseQueryOutput(output, "Peranta"),
        )
    }

    /** 該当する値名の行が無ければ null を返す。 */
    @Test
    fun returnsNullWhenValueAbsent() {
        val output = """
            HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run
                OtherApp    REG_SZ    C:\other\app.exe
        """.trimIndent()
        assertNull(WindowsRunRegistry.parseQueryOutput(output, "Peranta"))
    }

    /** クエリ・削除の reg 引数はキー・値名を正しく並べる。 */
    @Test
    fun buildsRegArguments() {
        assertEquals(
            listOf("reg", "query", WindowsRunRegistry.RUN_KEY, "/v", "Peranta"),
            WindowsRunRegistry.queryArgs("Peranta"),
        )
        assertEquals(
            listOf("reg", "delete", WindowsRunRegistry.RUN_KEY, "/v", "Peranta", "/f"),
            WindowsRunRegistry.deleteArgs("Peranta"),
        )
    }

    /** import 引数は .reg ファイルのパスをそのまま渡す。 */
    @Test
    fun buildsImportArguments() {
        assertEquals(
            listOf("reg", "import", "C:\\temp\\peranta.reg"),
            WindowsRunRegistry.importArgs("C:\\temp\\peranta.reg"),
        )
    }

    /**
     * .reg ファイルの本文は完全なハイブ名の角括弧行と、値名・データをダブルクォートで囲んだ行から成る。
     * データ内のダブルクォートと空白を含む起動コマンドは、バックスラッシュ・ダブルクォートをエスケープして
     * そのままテキストとして表現される（コマンドライン引数の再クォート規則の影響を受けない）。
     */
    @Test
    fun buildsRegFileContentWithEscapedCommand() {
        val command = "\"C:\\Program Files\\Peranta\\Peranta.exe\" --minimized"
        val content = WindowsRunRegistry.regFileContent("Peranta", command)
        assertEquals(
            "Windows Registry Editor Version 5.00\r\n" +
                "\r\n" +
                "[HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run]\r\n" +
                "\"Peranta\"=\"\\\"C:\\\\Program Files\\\\Peranta\\\\Peranta.exe\\\" --minimized\"\r\n",
            content,
        )
    }

    /** .reg ファイルは UTF-16LE の byte order mark（FF FE）で始まる必要がある（`reg import` の要求形式）。 */
    @Test
    fun regFileBytesStartWithUtf16LeBom() {
        val bytes = WindowsRunRegistry.regFileBytes("Peranta", "cmd")
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xFE.toByte(), bytes[1])
        val decoded = String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16LE"))
        assertTrue(decoded.contains("\"Peranta\"=\"cmd\""))
    }
}
