package to.sava.peranta.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.File
import java.io.IOException

/** APK の MIME タイプ。 */
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

/** FileProvider の authority 接尾辞（androidApp の manifest の provider と一致させる）。 */
private const val FILE_PROVIDER_SUFFIX = ".updates"

/** ダウンロード先ディレクトリ名（res/xml/file_paths.xml の cache-path と一致させる）。 */
private const val DOWNLOAD_DIR = "updates"

/** ダウンロードした APK のファイル名。 */
private const val APK_FILE_NAME = "peranta-update.apk"

/**
 * ダウンロードした APK の packageName が自アプリと一致するかを判定する（純粋関数）。
 * [actualPackage] は getPackageArchiveInfo が解析できないと null になる。
 */
fun isExpectedApkPackage(actualPackage: String?, expectedPackage: String): Boolean =
    actualPackage != null && actualPackage == expectedPackage

/**
 * Android の APK 自己更新（§12）。
 * [downloadAndLaunch] は APK をアプリ専用領域へ落とし、FileProvider 経由で
 * インストール確認の Intent を発行する（自動実行はしない。ユーザー操作でインストールされる）。
 *
 * 外部から落とした APK は、HTTP ステータスと packageName を検証してからインストールへ回す。
 */
class AndroidUpdateInstaller(
    private val context: Context,
    private val httpClient: HttpClient,
    private val log: Logger = Logger.withTag("UpdateInstaller"),
) {
    suspend fun downloadAndLaunch(url: String) {
        val apk = download(url)
        verifyPackage(apk)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}$FILE_PROVIDER_SUFFIX", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        log.i { "launched installer for downloaded apk" }
    }

    private suspend fun download(url: String): File {
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) {
            throw IOException("apk download failed: HTTP ${response.status.value}")
        }
        val dir = File(context.cacheDir, DOWNLOAD_DIR).apply { mkdirs() }
        val file = File(dir, APK_FILE_NAME)
        response.bodyAsChannel().toInputStream().use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    /** ダウンロード物が自アプリの APK であることを packageName で確認する。不一致は破棄して中断する。 */
    private fun verifyPackage(apk: File) {
        val packageName = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName
        if (!isExpectedApkPackage(packageName, context.packageName)) {
            apk.delete()
            throw IOException("apk package mismatch: expected=${context.packageName} actual=$packageName")
        }
    }
}
