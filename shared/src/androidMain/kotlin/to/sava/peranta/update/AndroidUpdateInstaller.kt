package to.sava.peranta.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import to.sava.peranta.android.fileProviderAuthority
import java.io.File

/** APK の MIME タイプ。 */
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

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
 * Android の APK 自己更新（§12）。APK をアプリ専用領域へ落とす、自アプリの APK かを確かめる、
 * FileProvider 経由でインストール確認の Intent を発行する、の 3 つを受け持つ（自動実行はしない。
 * ユーザー操作でインストールされる）。
 *
 * 外部から落とした APK は、HTTP ステータス・sha256・packageName を検証してからインストールへ回す。
 * HTTP ステータスは [download]、sha256 は [downloadVerifiedRelease]、packageName は [hasOwnPackageName] が
 * 確かめる。[hasOwnPackageName] と [launch] は sha256 を照合しないので、照合を通った配布物に対してだけ
 * 呼ぶ。この順序は [downloadAndHandOffApk] が守る。
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
        httpClient.downloadToFile(url, file, onProgress = onProgress)
        return file
    }

    /**
     * [apk] が自アプリの packageName を持つかを返す。解析できない APK は持たないとする。
     * 見るのは packageName だけなので、sha256 の照合を通った配布物に対して呼ぶ。
     * 破棄は呼び出し側が受け持つ（[handOffVerifiedApk]）。
     */
    fun hasOwnPackageName(apk: File): Boolean {
        val packageName = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName
        return isExpectedApkPackage(packageName, context.packageName).also { matches ->
            if (!matches) {
                log.w { "apk package mismatch: expected=${context.packageName} actual=$packageName" }
            }
        }
    }

    /**
     * インストール確認の Intent を発行する。実際のインストールはユーザーが承認して進む。
     * ここでは照合しないので、sha256 と packageName の照合を通った配布物に対して呼ぶ。
     */
    fun launch(apk: File) {
        val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context.packageName), apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        log.i { "launched installer for downloaded apk" }
    }
}
