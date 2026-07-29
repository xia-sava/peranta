package to.sava.peranta.config

import com.russhwolf.settings.Settings

/**
 * Desktop の秘密の保管庫を返す（§11）。
 *
 * Windows では DPAPI で包んでから settings（レジストリ）へ書く。DPAPI を使えない環境
 * （Windows 以外・ネイティブライブラリを読み込めない・呼び出しが失敗する）では、素のまま
 * 保存する実装へ退避して起動を続ける。
 */
actual fun createSecretStore(settings: Settings): SecretStore =
    WindowsDpapiProtector.availableOrNull()
        ?.let { ProtectedSecretStore(settings, it) }
        ?: SettingsSecretStore(settings)
