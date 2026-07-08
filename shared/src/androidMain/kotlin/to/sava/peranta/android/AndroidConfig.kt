package to.sava.peranta.android

import android.content.Context
import com.russhwolf.settings.SharedPreferencesSettings
import com.russhwolf.settings.Settings
import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.platform.AndroidApp

/** アプリ設定を保持する SharedPreferences 名。 */
private const val PREFS_NAME = "peranta-settings"

/** Android の SharedPreferences を multiplatform-settings に橋渡しする。 */
fun androidSettings(context: Context = AndroidApp.context): Settings =
    SharedPreferencesSettings(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

/** Android 用の [ConfigRepository] を生成する。鍵保管も同じ settings を使う。 */
fun androidConfigRepository(context: Context = AndroidApp.context): ConfigRepository =
    ConfigRepository(androidSettings(context))
