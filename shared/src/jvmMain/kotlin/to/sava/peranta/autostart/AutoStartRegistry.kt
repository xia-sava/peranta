package to.sava.peranta.autostart

import co.touchlab.kermit.Logger
import java.nio.charset.Charset
import java.nio.file.Files

/**
 * ログオン時自動起動の登録状態を読み書きする境界（§3.3）。
 * Windows では HKCU の Run キーを扱う。実装はレジストリ操作へ隔離し、
 * 登録判定・冪等な再登録などのロジックは [AutoStartManager] 側で純粋に扱えるようにする。
 */
interface AutoStartRegistry {
    /** 登録済みなら起動コマンド文字列を、未登録なら null を返す。 */
    fun currentCommand(): String?

    /** [command] を自動起動として登録する（既存があれば上書き）。登録後に読み戻して一致した場合のみ true を返す。 */
    fun register(command: String): Boolean

    /** 自動起動の登録を解除する。未登録でも無害に空振りする。 */
    fun unregister()
}

/**
 * HKCU の Run キーを `reg.exe` 経由で読み書きする [AutoStartRegistry] 実装（管理者権限不要）。
 * コマンド組み立てと出力解析は副作用の無い純関数（[Companion]）に切り出し、外部実行の境界だけを実装に残す。
 */
class WindowsRunRegistry(
    private val valueName: String = DEFAULT_VALUE_NAME,
    private val log: Logger = Logger.withTag("AutoStart"),
) : AutoStartRegistry {

    override fun currentCommand(): String? {
        val result = runProcess(queryArgs(valueName)) ?: return null
        if (result.exitCode != 0) return null
        return parseQueryOutput(result.output, valueName)
    }

    /**
     * [command] は `"exe パス" --minimized` のように内部にダブルクォートと空白を含み得る。
     * この形をコマンドライン引数として `reg add` に渡すと、ProcessBuilder（Windows）の引数再クォート規則と
     * 噛み合わず壊れるため、.reg ファイルへテキストとして書き出し `reg import` で登録する。
     */
    override fun register(command: String): Boolean {
        val regFile = Files.createTempFile("peranta-autostart", ".reg")
        try {
            Files.write(regFile, regFileBytes(valueName, command))
            val result = runProcess(importArgs(regFile.toString()))
            if (result == null || result.exitCode != 0) {
                log.w { "failed to register autostart (exit=${result?.exitCode})" }
                return false
            }
        } finally {
            Files.deleteIfExists(regFile)
        }
        return currentCommand() == command
    }

    override fun unregister() {
        val result = runProcess(deleteArgs(valueName))
        // 未登録での削除は exit!=0 になり得るため失敗として扱わない。
        if (result == null) {
            log.w { "failed to invoke reg for autostart removal" }
        }
    }

    private data class ProcessResult(val exitCode: Int, val output: String)

    private fun runProcess(args: List<String>): ProcessResult? =
        try {
            val process = ProcessBuilder(args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            ProcessResult(process.waitFor(), output)
        } catch (error: Exception) {
            log.w(error) { "reg execution failed: ${args.joinToString(" ")}" }
            null
        }

    companion object {
        /** Run キー内でこのアプリの登録を識別する値名。 */
        const val DEFAULT_VALUE_NAME = "Peranta"

        /** ログオン時自動起動を保持する HKCU レジストリキー（`reg.exe` の引数向け、省略形）。 */
        const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"

        /** 同じキーの完全なハイブ名表記。.reg ファイルの角括弧行は省略形（HKCU）を受け付けないため別に持つ。 */
        private const val RUN_KEY_FULL_HIVE = "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"

        /** .reg ファイルが要求する UTF-16LE の byte order mark。 */
        private val REG_FILE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())

        fun queryArgs(valueName: String): List<String> =
            listOf("reg", "query", RUN_KEY, "/v", valueName)

        fun importArgs(regFilePath: String): List<String> =
            listOf("reg", "import", regFilePath)

        fun deleteArgs(valueName: String): List<String> =
            listOf("reg", "delete", RUN_KEY, "/v", valueName, "/f")

        /**
         * `reg import` に渡す .reg ファイルの本文を組み立てる。
         * 値をコマンドライン引数でなくテキストとして書き出すため、内部にダブルクォートや空白を含む
         * 起動コマンドもエスケープだけで正確に表現できる。
         */
        fun regFileContent(valueName: String, command: String): String =
            "Windows Registry Editor Version 5.00\r\n" +
                "\r\n" +
                "[$RUN_KEY_FULL_HIVE]\r\n" +
                "\"${escapeRegString(valueName)}\"=\"${escapeRegString(command)}\"\r\n"

        /** .reg ファイルは UTF-16LE に BOM を付けて保存する（`reg import` が要求する形式）。 */
        fun regFileBytes(valueName: String, command: String): ByteArray =
            REG_FILE_BOM + regFileContent(valueName, command).toByteArray(Charset.forName("UTF-16LE"))

        /** .reg ファイルの文字列リテラル内でバックスラッシュとダブルクォートをエスケープする。 */
        private fun escapeRegString(value: String): String =
            value.replace("\\", "\\\\").replace("\"", "\\\"")

        /**
         * `reg query` の出力から [valueName] のデータ列を取り出す。
         * 出力は「値名<空白>型<空白>データ」の行を含み、データには空白やクォートが入り得るため、
         * 型トークン（REG_SZ / REG_EXPAND_SZ）以降をデータとして丸ごと取り出す。見つからなければ null。
         */
        fun parseQueryOutput(output: String, valueName: String): String? =
            output.lineSequence()
                .map { it.trim() }
                .firstNotNullOfOrNull { line -> dataFromLine(line, valueName) }

        private val TYPE_TOKENS = listOf("REG_SZ", "REG_EXPAND_SZ")

        private fun dataFromLine(line: String, valueName: String): String? {
            if (!line.startsWith(valueName)) return null
            val type = TYPE_TOKENS.firstOrNull { line.contains(it) } ?: return null
            return line.substringAfter(type).trim().ifEmpty { null }
        }
    }
}
