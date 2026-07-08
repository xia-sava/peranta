package to.sava.peranta.config

import com.russhwolf.settings.Settings
import dev.whyoleg.cryptography.random.CryptographyRandom
import to.sava.peranta.filter.FilterMode
import to.sava.peranta.filter.decodeFilterRules
import to.sava.peranta.filter.encodeFilterRules
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
            unifiedPushEndpoint = settings.getStringOrNull(KEY_UNIFIED_PUSH_ENDPOINT),
            sendEnabled = settings.getBoolean(KEY_SEND_ENABLED, false),
            smsDirectReceive = settings.getBoolean(KEY_SMS_DIRECT_RECEIVE, true),
            filterMode = loadFilterMode(),
            deliveryTopics = loadDeliveryTopics(),
            filterRules = decodeFilterRules(settings.getStringOrNull(KEY_FILTER_RULES)),
            persistSensitiveHistory = settings.getBoolean(KEY_PERSIST_SENSITIVE, false),
            otpSenderPackages = loadCsvList(KEY_OTP_SENDERS),
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
        putOrRemove(KEY_UNIFIED_PUSH_ENDPOINT, config.unifiedPushEndpoint)
        settings.putBoolean(KEY_SEND_ENABLED, config.sendEnabled)
        settings.putBoolean(KEY_SMS_DIRECT_RECEIVE, config.smsDirectReceive)
        settings.putString(KEY_FILTER_MODE, config.filterMode.name)
        settings.putString(KEY_DELIVERY_TOPICS, config.deliveryTopics.joinToString(TOPIC_SEPARATOR))
        settings.putString(KEY_FILTER_RULES, encodeFilterRules(config.filterRules))
        settings.putBoolean(KEY_PERSIST_SENSITIVE, config.persistSensitiveHistory)
        settings.putString(KEY_OTP_SENDERS, config.otpSenderPackages.joinToString(TOPIC_SEPARATOR))
        config.sharedKeyBase64
            ?.let { keyStore.storeKey(Base64.decode(it)) }
            ?: keyStore.clearKey()
    }

    private fun loadFilterMode(): FilterMode =
        settings.getStringOrNull(KEY_FILTER_MODE)
            ?.let { name -> FilterMode.entries.firstOrNull { it.name == name } }
            ?: FilterMode.DENYLIST

    private fun loadDeliveryTopics(): List<String> = loadCsvList(KEY_DELIVERY_TOPICS)

    /** [key] に区切り文字連結で保存された文字列一覧を読み出す。 */
    private fun loadCsvList(key: String): List<String> =
        settings.getStringOrNull(key)
            ?.split(TOPIC_SEPARATOR)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

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
        const val KEY_UNIFIED_PUSH_ENDPOINT = "unifiedPushEndpoint"
        const val KEY_SEND_ENABLED = "sendEnabled"
        const val KEY_SMS_DIRECT_RECEIVE = "smsDirectReceive"
        const val KEY_FILTER_MODE = "filterMode"
        const val KEY_DELIVERY_TOPICS = "deliveryTopics"
        const val KEY_FILTER_RULES = "filterRules"
        const val KEY_PERSIST_SENSITIVE = "persistSensitiveHistory"
        const val KEY_OTP_SENDERS = "otpSenderPackages"

        /** 配送先 topic を settings に 1 文字列で保持する際の区切り。 */
        private const val TOPIC_SEPARATOR = "\n"
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
