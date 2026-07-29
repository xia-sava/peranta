package to.sava.peranta.android

import android.content.Context
import android.content.pm.ApplicationInfo
import com.russhwolf.settings.SharedPreferencesSettings
import com.russhwolf.settings.Settings
import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.config.createSecretStore
import to.sava.peranta.platform.AndroidApp

/** アプリ設定を保持する SharedPreferences 名。 */
private const val PREFS_NAME = "peranta-settings"

/** Android の SharedPreferences を multiplatform-settings に橋渡しする。 */
fun androidSettings(
    context: Context = AndroidApp.context,
    prefsName: String = PREFS_NAME,
): Settings =
    SharedPreferencesSettings(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE))

/** debug ビルド（debuggable）かどうか。TLS 強制の境界に使う（§16）。 */
private fun isDebuggableBuild(context: Context): Boolean =
    context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

/**
 * Android 用の [ConfigRepository] を生成する。秘密の保管も同じ settings を使う。
 * リリースビルドでは TLS を常に有効へ強制し、debug ビルドでは保存値（既定は無効）を尊重する（§16）。
 */
fun androidConfigRepository(context: Context = AndroidApp.context): ConfigRepository =
    androidSettings(context).let { settings ->
        ConfigRepository(settings, createSecretStore(settings), forceTls = !isDebuggableBuild(context))
    }
