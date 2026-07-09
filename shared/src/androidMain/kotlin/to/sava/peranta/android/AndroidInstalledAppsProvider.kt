package to.sava.peranta.android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.withContext
import to.sava.peranta.platform.ioDispatcher
import to.sava.peranta.ui.InstalledApp
import to.sava.peranta.ui.InstalledAppsProvider
import java.text.Collator
import java.util.Locale

/** アイコンを縮小して保持するビットマップの一辺（px）。大きな一覧でもメモリを抑える。 */
private const val ICON_SIZE_PX = 96

/**
 * [PackageManager] からインストール済みアプリ一覧を組み立てる（§10.4 送信ロール）。
 * システムアプリ判定は実転送エンジンと同じランチャー有無判定（[packageManagerSystemPackagePredicate]）を
 * 用い、UI と転送の基準を揃える。アイコンは表示サイズ相当のビットマップへ縮小し、ラベルは [Collator] で
 * 日本語を含めて照合順に並べる。取得・変換は IO ディスパッチャで行う。
 */
class AndroidInstalledAppsProvider(
    context: Context,
    private val isCrossProfilePackage: Boolean = false,
) : InstalledAppsProvider {

    private val appContext = context.applicationContext

    override suspend fun loadInstalledApps(): List<InstalledApp> = withContext(ioDispatcher) {
        val packageManager = appContext.packageManager
        val isSystemApp = packageManagerSystemPackagePredicate(packageManager, isCrossProfilePackage)
        val collator = Collator.getInstance(Locale.getDefault())
        packageManager.getInstalledApplications(0)
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = packageManager.getApplicationLabel(info).toString(),
                    isSystemApp = isSystemApp(info.packageName),
                    icon = loadIcon(packageManager, info),
                )
            }
            .sortedWith(compareBy(collator) { it.label })
    }

    private fun loadIcon(packageManager: PackageManager, info: ApplicationInfo): ImageBitmap? =
        runCatching { packageManager.getApplicationIcon(info).toBoundedImageBitmap() }.getOrNull()

    private fun Drawable.toBoundedImageBitmap(): ImageBitmap {
        val bitmap = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
        setBounds(0, 0, ICON_SIZE_PX, ICON_SIZE_PX)
        draw(Canvas(bitmap))
        return bitmap.asImageBitmap()
    }
}
