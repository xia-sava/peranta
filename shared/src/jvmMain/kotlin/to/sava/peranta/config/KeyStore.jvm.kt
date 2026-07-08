package to.sava.peranta.config

import com.russhwolf.settings.Settings

actual fun createKeyStore(settings: Settings): KeyStore = SettingsKeyStore(settings)
