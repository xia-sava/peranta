package to.sava.peranta.update

import co.touchlab.kermit.Logger
import java.awt.Desktop
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

/** ブラウザで開くことを許す URL スキーム。 */
private val ALLOWED_SCHEMES = setOf("http", "https")

/**
 * [url] が http/https かつホストを持つ、ブラウザで開いてよい形式かを判定する（純粋関数）。
 * latest.json 由来の外部入力を Desktop.browse に渡す前の検証に使う。
 */
fun isBrowsableHttpUrl(url: String): Boolean {
    val uri = try {
        URI(url)
    } catch (e: URISyntaxException) {
        return false
    }
    return uri.scheme?.lowercase() in ALLOWED_SCHEMES && !uri.host.isNullOrEmpty()
}

/**
 * Desktop の自己更新（§12）。MSI の自動適用はせず、ダウンロード URL を既定ブラウザで開く。
 * url は latest.json 由来の外部入力のため、http/https のみを許可してから開く。
 */
class DesktopUpdateInstaller(
    private val log: Logger = Logger.withTag("UpdateInstaller"),
) {
    fun openDownloadPage(url: String) {
        if (!isBrowsableHttpUrl(url)) {
            log.w { "update url rejected: not a browsable http(s) url" }
            return
        }
        val uri = URI(url)
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            log.w { "cannot open update page: Desktop browse is unsupported on this platform" }
            return
        }
        try {
            Desktop.getDesktop().browse(uri)
            log.i { "opened update page in browser" }
        } catch (e: IOException) {
            log.e(e) { "failed to open update page in browser" }
        }
    }
}
