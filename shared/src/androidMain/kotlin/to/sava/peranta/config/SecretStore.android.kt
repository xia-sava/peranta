package to.sava.peranta.config

import com.russhwolf.settings.Settings

/**
 * Android の秘密の保管庫を返す（§11）。
 *
 * 保存先はアプリ専用の `SharedPreferences` で、アプリサンドボックスにより同じ端末の他アプリからは
 * 読めず、`allowBackup="false"` によりバックアップ経路にも出ない。Android Keystore で包み直しても
 * 守れる相手が増えるのは端末を物理的に握られた場合に限られるため、この層は素のまま保存する。
 */
actual fun createSecretStore(settings: Settings): SecretStore = SettingsSecretStore(settings)
