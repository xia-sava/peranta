package to.sava.peranta.android

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import co.touchlab.kermit.Logger

private val log = Logger.withTag("Share")

/** 共有シートから受け取れる唯一のスキーム。 */
private const val CONTENT_SCHEME = "content"

/** 表示名も末尾パスも引けなかった添付に付ける名前。 */
private const val FALLBACK_FILE_NAME = "image"

/**
 * 共有シート（`ACTION_SEND` / `ACTION_SEND_MULTIPLE`）で渡された Uri を読み出してよいか判定する（§4.3）。
 *
 * 受け口は exported なので、共有元アプリを名乗る任意のアプリが Uri を積んで直接起動できる。
 * 起動元は特定できない（`getCallingPackage` は結果を返す起動でないと取れない）ため、
 * 判定は「誰が渡したか」ではなく「**Peranta 自身の権限でしか開けない先を指していないか**」で行う。
 *
 * - `content` 以外のスキームは拒否する。`file` はアプリのデータディレクトリを直接指せるため、
 *   共有鍵やアクセストークンを持つ設定ファイル・通知履歴を読ませる経路になる。
 * - 自パッケージが持つ ContentProvider の Uri も拒否する。この Provider は他アプリへ公開しておらず、
 *   渡す側が自分では開けないものを Peranta に開かせるためだけの経路になる。
 *
 * [providerPackageName] は authority を解決した ContentProvider の所属パッケージで、解決できなければ null。
 */
fun isAcceptedSharedStream(
    scheme: String?,
    providerPackageName: String?,
    selfPackageName: String,
): Boolean = scheme.equals(CONTENT_SCHEME, ignoreCase = true) && providerPackageName != selfPackageName

/** 共有シートで渡された Uri のうち、[isAcceptedSharedStream] を満たすものだけを残す。 */
fun Context.acceptedSharedStreams(uris: List<Uri>): List<Uri> =
    uris.filter { uri ->
        isAcceptedSharedStream(
            scheme = uri.scheme,
            providerPackageName = uri.authority?.let { providerPackageName(it) },
            selfPackageName = packageName,
        )
    }.also { accepted ->
        (uris.size - accepted.size)
            .takeIf { it > 0 }
            ?.let { log.w { "rejected $it shared uri(s) addressing data only this app can read" } }
    }

/**
 * 共有シートで渡された Uri の表示名（§4.3）。転送時に載せるファイル名と同じ値を返し、
 * 送信前に確認する名前（§10.1）と実際に送る名前が食い違わないようにする。
 */
fun Context.sharedStreamDisplayName(uri: Uri): String =
    queryDisplayName(uri) ?: uri.lastPathSegment ?: FALLBACK_FILE_NAME

private fun Context.queryDisplayName(uri: Uri): String? =
    try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            cursor.takeIf { it.moveToFirst() }
                ?.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                ?.takeIf { it >= 0 }
                ?.let { cursor.getString(it) }
        }
    } catch (error: Exception) {
        log.w { "failed to query shared stream display name (${error::class.simpleName})" }
        null
    }

/** authority を宣言している ContentProvider の所属パッケージ。解決できなければ null。 */
private fun Context.providerPackageName(authority: String): String? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.resolveContentProvider(authority, PackageManager.ComponentInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        packageManager.resolveContentProvider(authority, 0)
    }?.packageName
