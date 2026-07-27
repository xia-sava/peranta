package to.sava.peranta.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
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
 * Android の APK 自己更新（§12）。APK をアプリ専用領域へ落とし、照合してから
 * FileProvider 経由でインストール確認の Intent を発行する（自動実行はしない。
 * ユーザー操作でインストールされる）。
 *
 * 外部から落とした APK は、HTTP ステータス・sha256・packageName を検証してからインストールへ回す。
 */
class AndroidUpdateInstaller(
    private val context: Context,
    private val httpClient: HttpClient,
    private val log: Logger = Logger.withTag("UpdateInstaller"),
) {

    /**
     * 配布物をダウンロードしてアプリ専用領域へ置く。受信量を [onProgress] へ逐次知らせる
     * （全体長が判らなければ 0 を渡す）。
     */
    suspend fun download(url: String, onProgress: (received: Long, total: Long) -> Unit = { _, _ -> }): File {
        val dir = File(context.cacheDir, DOWNLOAD_DIR).apply { mkdirs() }
        val file = File(dir, APK_FILE_NAME)
        httpClient.downloadToFile(url, file, onProgress)
        return file
    }

    /**
     * ダウンロード物が配布元の意図した実体で、かつ自アプリの APK であることを確かめる。
     * どちらかが食い違えば破棄して中断する。
     */
    fun verify(apk: File, expectedSha256: String?) {
        if (!matchesSha256(apk, expectedSha256)) {
            apk.delete()
            throw IOException("apk digest mismatch")
        }
        val packageName = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName
        if (!isExpectedApkPackage(packageName, context.packageName)) {
            apk.delete()
            throw IOException("apk package mismatch: expected=${context.packageName} actual=$packageName")
        }
    }

    /** インストール確認の Intent を発行する。実際のインストールはユーザーが承認して進む。 */
    fun launch(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}$FILE_PROVIDER_SUFFIX", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        log.i { "launched installer for downloaded apk" }
    }
}
