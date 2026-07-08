package to.sava.peranta.config

import com.russhwolf.settings.Settings
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlin.io.encoding.Base64

/**
 * [PerantaConfig] を multiplatform-settings に読み書きする。
 * 共有鍵の実体だけは [KeyStore] 経由で保管し、その他の項目は settings に直接保存する。
 */
class ConfigRepository(
    private val settings: Settings,
    private val keyStore: KeyStore = createKeyStore(settings),
) {

    fun load(): PerantaConfig {
        val sharedKeyBase64 = keyStore.loadKey()?.let { Base64.encode(it) }
        return PerantaConfig(
            host = settings.getString(KEY_HOST, DEFAULT_HOST),
            useTls = settings.getBoolean(KEY_USE_TLS, true),
            port = if (settings.hasKey(KEY_PORT)) settings.getInt(KEY_PORT, 0) else null,
            accessToken = settings.getStringOrNull(KEY_TOKEN),
            deviceName = settings.getStringOrNull(KEY_DEVICE_NAME),
            sharedKeyBase64 = sharedKeyBase64,
            keyId = settings.getStringOrNull(KEY_KEY_ID),
            receiveTopic = settings.getStringOrNull(KEY_RECEIVE_TOPIC),
        )
    }

    fun save(config: PerantaConfig) {
        settings.putString(KEY_HOST, config.host)
        settings.putBoolean(KEY_USE_TLS, config.useTls)
        config.port?.let { settings.putInt(KEY_PORT, it) } ?: settings.remove(KEY_PORT)
        putOrRemove(KEY_TOKEN, config.accessToken)
        putOrRemove(KEY_DEVICE_NAME, config.deviceName)
        putOrRemove(KEY_KEY_ID, config.keyId)
        putOrRemove(KEY_RECEIVE_TOPIC, config.receiveTopic)
        config.sharedKeyBase64
            ?.let { keyStore.storeKey(Base64.decode(it)) }
            ?: keyStore.clearKey()
    }

    /**
     * 自分の受信 topic を返す。未設定なら端末名から生成して永続化する。
     */
    fun ensureReceiveTopic(deviceName: String): String =
        settings.getStringOrNull(KEY_RECEIVE_TOPIC) ?: generateReceiveTopic(deviceName).also {
            settings.putString(KEY_RECEIVE_TOPIC, it)
        }

    private fun putOrRemove(key: String, value: String?) {
        value?.let { settings.putString(key, it) } ?: settings.remove(key)
    }

    companion object {
        const val KEY_HOST = "host"
        const val KEY_USE_TLS = "useTls"
        const val KEY_PORT = "port"
        const val KEY_TOKEN = "accessToken"
        const val KEY_DEVICE_NAME = "deviceName"
        const val KEY_KEY_ID = "keyId"
        const val KEY_RECEIVE_TOPIC = "receiveTopic"
    }
}

private const val RANDOM_SUFFIX_LENGTH = 16
private const val TOPIC_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

/** Desktop 受信端末のエンドポイント topic を採番する（§8）。 */
fun generateReceiveTopic(deviceName: String): String {
    val suffix = buildString {
        repeat(RANDOM_SUFFIX_LENGTH) {
            append(TOPIC_ALPHABET[CryptographyRandom.Default.nextInt(TOPIC_ALPHABET.length)])
        }
    }
    val safeName = deviceName.lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .trim('-')
        .ifEmpty { "device" }
    return "peranta-dev-$safeName-$suffix"
}
