package to.sava.peranta.config

import com.russhwolf.settings.Settings
import kotlin.io.encoding.Base64

/**
 * 共有鍵のローカル保管を抽象化する。
 * v1 は settings に base64 で素直に保存する。将来 Android Keystore / Windows DPAPI 等へ
 * expect/actual で差し替えられるよう、生成を [createKeyStore] に集約する。
 */
interface KeyStore {
    fun loadKey(): ByteArray?
    fun storeKey(key: ByteArray)
    fun clearKey()
}

/** settings に base64 文字列として鍵を保存する既定実装。 */
class SettingsKeyStore(
    private val settings: Settings,
    private val key: String = KEY_SHARED_KEY,
) : KeyStore {

    override fun loadKey(): ByteArray? =
        settings.getStringOrNull(key)?.let { Base64.decode(it) }

    override fun storeKey(key: ByteArray) {
        settings.putString(this.key, Base64.encode(key))
    }

    override fun clearKey() {
        settings.remove(key)
    }

    companion object {
        const val KEY_SHARED_KEY: String = "sharedKey"
    }
}

/** プラットフォーム毎の鍵保管を返す。 */
expect fun createKeyStore(settings: Settings): KeyStore
