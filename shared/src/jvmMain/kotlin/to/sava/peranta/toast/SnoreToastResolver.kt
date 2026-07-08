package to.sava.peranta.toast

import co.touchlab.kermit.Logger
import to.sava.peranta.platform.JvmPaths
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.absolutePathString

/** 同梱リソース上の snoretoast.exe のクラスパス名。 */
private const val SNORETOAST_RESOURCE = "/snoretoast.exe"

/** ショートカット二重登録を防ぐマーカーファイル名。 */
private const val INSTALL_MARKER = "snoretoast-installed"

/** 現在の OS が Windows なら true。 */
private fun isWindows(): Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

/**
 * Desktop 用 [Toaster] を構築する。同梱 exe をアプリ専用領域へ展開し、
 * 初回のみショートカット登録（-install）を行う。非対応環境では [NoOpToaster] を返す。
 */
fun createDesktopToaster(log: Logger = Logger.withTag("Toaster")): Toaster {
    val exe = resolveSnoreToastExe(log) ?: return NoOpToaster
    ensureShortcutInstalled(exe, log)
    return SnoreToastToaster(exe, log)
}

/**
 * 同梱の snoretoast.exe をアプリ専用領域へ展開し、その安定パスを返す。
 * 非 Windows・リソース欠如時は null を返す。
 *
 * persistent トーストがアプリ終了後も同名 exe をロックし続けるため、無条件コピーは避ける。
 * 既存ファイルが同梱物とサイズ + sha256 で一致するならコピーせずそのまま使い、
 * 内容が異なる（更新後）のにロックで上書きできない場合はハッシュ付き別名へ展開してそちらを使う。
 */
private fun resolveSnoreToastExe(log: Logger): Path? {
    if (!isWindows()) {
        log.w { "toast disabled: not running on Windows" }
        return null
    }
    val bundled = readBundledExe(log) ?: return null
    val hash = sha256Hex(bundled)
    val appDir = JvmPaths.appDir.toPath()

    val primary = appDir.resolve("snoretoast.exe")
    if (fileMatches(primary, bundled)) {
        log.i { "snoretoast.exe up to date at $primary" }
        return primary
    }
    writeExe(primary, bundled)?.let {
        log.i { "snoretoast.exe extracted to $it" }
        return it
    }

    log.w { "snoretoast.exe at $primary is locked or unwritable; falling back to versioned copy" }
    val versioned = appDir.resolve("snoretoast-${hash.take(HASH_NAME_LENGTH)}.exe")
    if (fileMatches(versioned, bundled)) {
        log.i { "snoretoast.exe up to date at $versioned" }
        return versioned
    }
    writeExe(versioned, bundled)?.let {
        log.i { "snoretoast.exe extracted to $it" }
        return it
    }
    log.e { "failed to extract snoretoast.exe to either $primary or $versioned" }
    return null
}

/** 同梱 exe をバイト列として読み出す。リソース欠如は null。 */
private fun readBundledExe(log: Logger): ByteArray? {
    val resource = SnoreToastCommand::class.java.getResourceAsStream(SNORETOAST_RESOURCE)
    if (resource == null) {
        log.w { "toast disabled: bundled snoretoast.exe not found on classpath" }
        return null
    }
    return resource.use { it.readBytes() }
}

/** [path] が存在し、その内容が同梱物と一致するなら true。読み取り不能時は false。 */
private fun fileMatches(path: Path, bundled: ByteArray): Boolean =
    try {
        Files.exists(path) && snoreToastExeMatches(Files.readAllBytes(path), bundled)
    } catch (e: IOException) {
        false
    }

/** [bundled] を [path] へ書き出す。ロック等で書けなければ null を返す。 */
private fun writeExe(path: Path, bundled: ByteArray): Path? =
    try {
        Files.write(path, bundled)
    } catch (e: IOException) {
        null
    }

/** 既存 exe と同梱 exe が同一内容か（サイズ + sha256 で判定）。コピー要否の純粋な判断。 */
internal fun snoreToastExeMatches(existing: ByteArray, bundled: ByteArray): Boolean =
    existing.size == bundled.size && sha256Hex(existing) == sha256Hex(bundled)

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(separator = "") { "%02x".format(it) }

/** ハッシュ付き別名で使う 16 進ハッシュの先頭文字数。 */
private const val HASH_NAME_LENGTH = 8

/** ショートカット未登録ならば -install を 1 回だけ実行し、マーカーで再登録を防ぐ。 */
private fun ensureShortcutInstalled(exe: Path, log: Logger) {
    val marker = JvmPaths.appDir.toPath().resolve(INSTALL_MARKER)
    if (Files.exists(marker)) return
    val args = SnoreToastCommand.installArgs(exe.absolutePathString(), SnoreToastCommand.APP_USER_MODEL_ID)
    val code = try {
        ProcessBuilder(args).redirectErrorStream(true).start().waitFor()
    } catch (e: IOException) {
        log.w(e) { "snoretoast -install failed to launch" }
        return
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        log.w(e) { "snoretoast -install interrupted" }
        return
    }
    if (code == 0) {
        Files.writeString(marker, SnoreToastCommand.APP_USER_MODEL_ID)
        log.i { "registered AppUserModelID shortcut for ${SnoreToastCommand.APP_USER_MODEL_ID}" }
    } else {
        log.w { "snoretoast -install returned exit=$code" }
    }
}
