package to.sava.peranta.config

import com.russhwolf.settings.Settings
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import to.sava.peranta.filter.FilterMode
import to.sava.peranta.filter.FilterRule
import to.sava.peranta.filter.decodeFilterRules
import to.sava.peranta.filter.encodeFilterRules
import kotlin.io.encoding.Base64
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
            deviceId = settings.getStringOrNull(KEY_DEVICE_ID),
            deviceName = settings.getStringOrNull(KEY_DEVICE_NAME),
            sharedKeyBase64 = sharedKeyBase64,
            keyId = settings.getStringOrNull(KEY_KEY_ID),
            receiveTopic = settings.getStringOrNull(KEY_RECEIVE_TOPIC),
            controlTopic = settings.getStringOrNull(KEY_CONTROL_TOPIC),
            unifiedPushEndpoint = settings.getStringOrNull(KEY_UNIFIED_PUSH_ENDPOINT),
            sendEnabled = settings.getBoolean(KEY_SEND_ENABLED, false),
            smsDirectReceive = settings.getBoolean(KEY_SMS_DIRECT_RECEIVE, true),
            filterMode = loadFilterMode(),
            deliveryTopics = loadDeliveryTopics(),
            filterRules = decodeFilterRules(settings.getStringOrNull(KEY_FILTER_RULES)),
            persistSensitiveHistory = settings.getBoolean(KEY_PERSIST_SENSITIVE, false),
            otpSenderPackages = loadCsvList(KEY_OTP_SENDERS),
            revokedDeviceIds = loadCsvList(KEY_REVOKED_DEVICE_IDS).toSet(),
        )
    }

    /**
     * 設定を全キー書き戻しで保存する。[updateFilterRules] と同じ排他ロックを取り、
     * 端末内で並行する load→変更→save と updateFilterRules（mute/unmute）の書き込みが
     * 互いを破壊しないようにする（詳細は [configMutex] を参照）。
     */
    fun save(config: PerantaConfig): Unit = runBlocking {
        configMutex.withLock { saveLocked(config) }
    }

    private fun saveLocked(config: PerantaConfig) {
        settings.putString(KEY_HOST, config.host)
        settings.putBoolean(KEY_USE_TLS, config.useTls)
        config.port?.let { settings.putInt(KEY_PORT, it) } ?: settings.remove(KEY_PORT)
        putOrRemove(KEY_TOKEN, config.accessToken)
        putOrRemove(KEY_DEVICE_ID, config.deviceId)
        putOrRemove(KEY_DEVICE_NAME, config.deviceName)
        putOrRemove(KEY_KEY_ID, config.keyId)
        putOrRemove(KEY_RECEIVE_TOPIC, config.receiveTopic)
        putOrRemove(KEY_CONTROL_TOPIC, config.controlTopic)
        putOrRemove(KEY_UNIFIED_PUSH_ENDPOINT, config.unifiedPushEndpoint)
        settings.putBoolean(KEY_SEND_ENABLED, config.sendEnabled)
        settings.putBoolean(KEY_SMS_DIRECT_RECEIVE, config.smsDirectReceive)
        settings.putString(KEY_FILTER_MODE, config.filterMode.name)
        settings.putString(KEY_DELIVERY_TOPICS, config.deliveryTopics.joinToString(TOPIC_SEPARATOR))
        settings.putString(KEY_FILTER_RULES, encodeFilterRules(config.filterRules))
        settings.putBoolean(KEY_PERSIST_SENSITIVE, config.persistSensitiveHistory)
        settings.putString(KEY_OTP_SENDERS, config.otpSenderPackages.joinToString(TOPIC_SEPARATOR))
        settings.putString(KEY_REVOKED_DEVICE_IDS, config.revokedDeviceIds.joinToString(TOPIC_SEPARATOR))
        config.sharedKeyBase64
            ?.let { keyStore.storeKey(Base64.decode(it)) }
            ?: keyStore.clearKey()
    }

    /**
     * フィルタルールだけを排他的に読み書き更新する（§7 のアプリフィルタ操作向け）。
     * [save] と違い共有鍵の再保存など他項目には触れないため、チェックボックス操作のたびに呼んでも軽い。
     * [transform] が入力と同じインスタンスを返したときは変化なしとみなし、書き込みを省く。
     * 更新後のルール一覧を返す。排他制御は [save] と共通のロックで行い、
     * 端末内で並行する load→変更→save の競合を避ける（詳細は [configMutex] を参照）。
     */
    suspend fun updateFilterRules(
        transform: (List<FilterRule>) -> List<FilterRule>,
    ): List<FilterRule> = configMutex.withLock {
        val current = decodeFilterRules(settings.getStringOrNull(KEY_FILTER_RULES))
        val updated = transform(current)
        if (updated !== current) {
            settings.putString(KEY_FILTER_RULES, encodeFilterRules(updated))
        }
        updated
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

    /**
     * この端末の安定 ID を返す。未設定なら生成して永続化する。
     * 一度確定した ID は端末名を変えても不変で、ペイロードの from/to に使う。
     */
    fun ensureDeviceId(): String =
        settings.getStringOrNull(KEY_DEVICE_ID) ?: generateDeviceId().also {
            settings.putString(KEY_DEVICE_ID, it)
        }

    /**
     * control topic を返す。未設定なら生成して永続化する（§8）。
     * 全端末で共有する topic のため、設定元端末で確定した値をペアリングで配布する。
     */
    fun ensureControlTopic(): String =
        settings.getStringOrNull(KEY_CONTROL_TOPIC) ?: generateControlTopic().also {
            settings.putString(KEY_CONTROL_TOPIC, it)
        }

    private fun putOrRemove(key: String, value: String?) {
        value?.let { settings.putString(key, it) } ?: settings.remove(key)
    }

    companion object {
        const val KEY_HOST = "host"
        const val KEY_USE_TLS = "useTls"
        const val KEY_PORT = "port"
        const val KEY_TOKEN = "accessToken"
        const val KEY_DEVICE_ID = "deviceId"
        const val KEY_DEVICE_NAME = "deviceName"
        const val KEY_KEY_ID = "keyId"
        const val KEY_RECEIVE_TOPIC = "receiveTopic"
        const val KEY_CONTROL_TOPIC = "controlTopic"
        const val KEY_UNIFIED_PUSH_ENDPOINT = "unifiedPushEndpoint"
        const val KEY_SEND_ENABLED = "sendEnabled"
        const val KEY_SMS_DIRECT_RECEIVE = "smsDirectReceive"
        const val KEY_FILTER_MODE = "filterMode"
        const val KEY_DELIVERY_TOPICS = "deliveryTopics"
        const val KEY_FILTER_RULES = "filterRules"
        const val KEY_PERSIST_SENSITIVE = "persistSensitiveHistory"
        const val KEY_OTP_SENDERS = "otpSenderPackages"
        const val KEY_REVOKED_DEVICE_IDS = "revokedDeviceIds"

        /** 配送先 topic を settings に 1 文字列で保持する際の区切り。 */
        private const val TOPIC_SEPARATOR = "\n"

        /**
         * [save] と [updateFilterRules] の書き込みを直列化するロック。
         * [ConfigRepository] は同じ設定ストアに対して都度生成されるため、インスタンス間で共有できるよう
         * companion に置く。両者の内部処理はいずれも中断を挟まない同期処理のみで、互いを再帰的に
         * 呼び出すこともないため、非再入ロックのままデッドロックは起きない。この前提を崩さないよう、
         * [save]・[updateFilterRules] の実装からは互いを呼び出さないこと。
         */
        private val configMutex = Mutex()
    }
}

private const val RANDOM_SUFFIX_LENGTH = 16
private const val TOPIC_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

/** 推測困難な topic 末尾のランダム文字列を生成する（§8）。 */
private fun randomTopicSuffix(): String = buildString {
    repeat(RANDOM_SUFFIX_LENGTH) {
        append(TOPIC_ALPHABET[CryptographyRandom.Default.nextInt(TOPIC_ALPHABET.length)])
    }
}

/** Desktop 受信端末のエンドポイント topic を採番する（§8）。 */
fun generateReceiveTopic(deviceName: String): String {
    val safeName = deviceName.lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .trim('-')
        .ifEmpty { "device" }
    return "peranta-dev-$safeName-${randomTopicSuffix()}"
}

/** 全端末共有の control topic を採番する（§8）。 */
fun generateControlTopic(): String = "peranta-control-${randomTopicSuffix()}"

/** 端末の安定 ID（ランダム UUID）を生成する。 */
@OptIn(ExperimentalUuidApi::class)
fun generateDeviceId(): String = Uuid.random().toString()
