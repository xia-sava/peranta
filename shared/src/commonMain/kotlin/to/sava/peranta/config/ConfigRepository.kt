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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * [PerantaConfig] を multiplatform-settings に読み書きする。
 * 共有鍵とアクセストークンは [SecretStore] 経由で保管し、その他の項目は settings に直接保存する。
 *
 * [forceTls] が真（リリースビルド・既定）のとき TLS は常に有効として読み出し、保存もしない。
 * 偽（Android の debug ビルド / Desktop の devMode）のときは保存値を尊重するが、既定は有効で、
 * 平文にするには明示して落とす必要がある。開発でも接続先は TLS の本番サーバであることが通例で、
 * 既定を無効にすると保存値を持たない端末が黙って平文へ落ちるため。境界はビルド種別であり、
 * 実行時に切り替わることはない（§16）。
 */
class ConfigRepository(
    private val settings: Settings,
    private val secretStore: SecretStore = createSecretStore(settings),
    private val forceTls: Boolean = true,
) {

    fun load(): PerantaConfig =
        PerantaConfig(
            host = settings.getString(KEY_HOST, DEFAULT_HOST),
            useTls = if (forceTls) true else settings.getBoolean(KEY_USE_TLS, true),
            port = if (settings.hasKey(KEY_PORT)) settings.getInt(KEY_PORT, 0) else null,
            accessToken = secretStore.loadSecret(SECRET_ACCESS_TOKEN),
            deviceId = settings.getStringOrNull(KEY_DEVICE_ID),
            deviceName = settings.getStringOrNull(KEY_DEVICE_NAME),
            sharedKeyBase64 = secretStore.loadSecret(SECRET_SHARED_KEY),
            keyId = settings.getStringOrNull(KEY_KEY_ID),
            receiveTopic = settings.getStringOrNull(KEY_RECEIVE_TOPIC),
            controlTopic = settings.getStringOrNull(KEY_CONTROL_TOPIC),
            blobTopic = settings.getStringOrNull(KEY_BLOB_TOPIC),
            unifiedPushEndpoint = settings.getStringOrNull(KEY_UNIFIED_PUSH_ENDPOINT),
            sendEnabled = settings.getBoolean(KEY_SEND_ENABLED, false),
            smsDirectReceive = settings.getBoolean(KEY_SMS_DIRECT_RECEIVE, true),
            forwardWorkProfileNotifications = settings.getBoolean(KEY_FORWARD_WORK_PROFILE, false),
            filterMode = loadFilterMode(),
            deliveryTopics = loadDeliveryTopics(),
            filterRules = decodeFilterRules(settings.getStringOrNull(KEY_FILTER_RULES)),
            persistSensitiveHistory = settings.getBoolean(KEY_PERSIST_SENSITIVE, false),
            otpSenderPackages = loadCsvList(KEY_OTP_SENDERS),
            attachFullTextWhenTruncated = settings.getBoolean(KEY_ATTACH_FULL_TEXT, true),
            timelineRetentionDays = ensureTimelineRetentionDays(),
            autoDisplayImages = settings.getBoolean(KEY_AUTO_DISPLAY_IMAGES, true),
            attachNotificationImages = settings.getBoolean(KEY_ATTACH_NOTIFICATION_IMAGES, true),
            verboseLogging = settings.getBoolean(KEY_VERBOSE_LOGGING, false),
        )

    /**
     * 設定を全キー書き戻しで保存する。[updateFilterRules] と同じ排他ロックを取り、
     * 端末内で並行する load→変更→save と updateFilterRules（mute/unmute）の書き込みが
     * 互いを破壊しないようにする（詳細は [configMutex] を参照）。
     */
    fun save(config: PerantaConfig): Unit = runBlocking {
        configMutex.withLock { saveLocked(config) }
    }

    private fun saveLocked(config: PerantaConfig) {
        UNUSED_KEYS.forEach { settings.remove(it) }
        settings.putString(KEY_HOST, config.host)
        if (!forceTls) settings.putBoolean(KEY_USE_TLS, config.useTls)
        config.port?.let { settings.putInt(KEY_PORT, it) } ?: settings.remove(KEY_PORT)
        putOrClearSecret(SECRET_ACCESS_TOKEN, config.accessToken)
        putOrRemove(KEY_DEVICE_ID, config.deviceId)
        putOrRemove(KEY_DEVICE_NAME, config.deviceName)
        putOrRemove(KEY_KEY_ID, config.keyId)
        putOrRemove(KEY_RECEIVE_TOPIC, config.receiveTopic)
        putOrRemove(KEY_CONTROL_TOPIC, config.controlTopic)
        putOrRemove(KEY_BLOB_TOPIC, config.blobTopic)
        putOrRemove(KEY_UNIFIED_PUSH_ENDPOINT, config.unifiedPushEndpoint)
        settings.putBoolean(KEY_SEND_ENABLED, config.sendEnabled)
        settings.putBoolean(KEY_SMS_DIRECT_RECEIVE, config.smsDirectReceive)
        settings.putBoolean(KEY_FORWARD_WORK_PROFILE, config.forwardWorkProfileNotifications)
        settings.putString(KEY_FILTER_MODE, config.filterMode.name)
        settings.putString(KEY_DELIVERY_TOPICS, config.deliveryTopics.joinToString(TOPIC_SEPARATOR))
        settings.putString(KEY_FILTER_RULES, encodeFilterRules(config.filterRules))
        settings.putBoolean(KEY_PERSIST_SENSITIVE, config.persistSensitiveHistory)
        settings.putString(KEY_OTP_SENDERS, config.otpSenderPackages.joinToString(TOPIC_SEPARATOR))
        settings.putBoolean(KEY_ATTACH_FULL_TEXT, config.attachFullTextWhenTruncated)
        config.timelineRetentionDays
            ?.let { settings.putInt(KEY_TIMELINE_RETENTION_DAYS, it) }
            ?: settings.remove(KEY_TIMELINE_RETENTION_DAYS)
        settings.putBoolean(KEY_AUTO_DISPLAY_IMAGES, config.autoDisplayImages)
        settings.putBoolean(KEY_ATTACH_NOTIFICATION_IMAGES, config.attachNotificationImages)
        settings.putBoolean(KEY_VERBOSE_LOGGING, config.verboseLogging)
        putOrClearSecret(SECRET_SHARED_KEY, config.sharedKeyBase64)
    }

    /**
     * 端末に保存した設定を全て消す（§11）。共有鍵とアクセストークンも破棄するため、
     * 以後は初期設定からやり直しになる。
     * 秘密の保管先は settings の外にある実装もあるため、settings の消去とは別に [SecretStore] へも消去を求める。
     */
    fun clear(): Unit = runBlocking {
        configMutex.withLock {
            SECRET_NAMES.forEach { secretStore.clearSecret(it) }
            settings.clear()
        }
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

    /**
     * タイムライン履歴の保持日数を返す（§11）。保存済みの値があればそれを使う。
     * 値が無く、かつ設定が 1 件も保存されていない端末（＝インストール直後）には
     * [DEFAULT_TIMELINE_RETENTION_DAYS] を採番して永続化する。既に使っている端末では
     * null（日数による剪定なし）のままとし、保持日数を自分で決めていない利用者の履歴が
     * 更新をきっかけに消えないようにする。
     */
    private fun ensureTimelineRetentionDays(): Int? = when {
        settings.hasKey(KEY_TIMELINE_RETENTION_DAYS) -> settings.getInt(KEY_TIMELINE_RETENTION_DAYS, 0)
        settings.keys.isNotEmpty() -> null
        else -> DEFAULT_TIMELINE_RETENTION_DAYS.also { settings.putInt(KEY_TIMELINE_RETENTION_DAYS, it) }
    }

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

    /**
     * blob topic を返す。未設定なら生成して永続化する（§8、§4.3）。
     * 全端末で共有する topic のため、設定元端末で確定した値をペアリングで配布する。
     */
    fun ensureBlobTopic(): String =
        settings.getStringOrNull(KEY_BLOB_TOPIC) ?: generateBlobTopic().also {
            settings.putString(KEY_BLOB_TOPIC, it)
        }

    private fun putOrRemove(key: String, value: String?) {
        value?.let { settings.putString(key, it) } ?: settings.remove(key)
    }

    private fun putOrClearSecret(name: String, value: String?) {
        value?.let { secretStore.storeSecret(name, it) } ?: secretStore.clearSecret(name)
    }

    companion object {
        const val KEY_HOST = "host"
        const val KEY_USE_TLS = "useTls"
        const val KEY_PORT = "port"
        const val KEY_DEVICE_ID = "deviceId"
        const val KEY_DEVICE_NAME = "deviceName"
        const val KEY_KEY_ID = "keyId"
        const val KEY_RECEIVE_TOPIC = "receiveTopic"
        const val KEY_CONTROL_TOPIC = "controlTopic"
        const val KEY_BLOB_TOPIC = "blobTopic"
        const val KEY_UNIFIED_PUSH_ENDPOINT = "unifiedPushEndpoint"
        const val KEY_SEND_ENABLED = "sendEnabled"
        const val KEY_SMS_DIRECT_RECEIVE = "smsDirectReceive"
        const val KEY_FORWARD_WORK_PROFILE = "forwardWorkProfileNotifications"
        const val KEY_FILTER_MODE = "filterMode"
        const val KEY_DELIVERY_TOPICS = "deliveryTopics"
        const val KEY_FILTER_RULES = "filterRules"
        const val KEY_PERSIST_SENSITIVE = "persistSensitiveHistory"
        const val KEY_OTP_SENDERS = "otpSenderPackages"
        const val KEY_ATTACH_FULL_TEXT = "attachFullTextWhenTruncated"
        const val KEY_TIMELINE_RETENTION_DAYS = "timelineRetentionDays"
        const val KEY_AUTO_DISPLAY_IMAGES = "autoDisplayImages"
        const val KEY_ATTACH_NOTIFICATION_IMAGES = "attachNotificationImages"
        const val KEY_VERBOSE_LOGGING = "verboseLogging"

        /**
         * インストール直後の端末に与えるタイムライン履歴の保持日数（§11）。
         * 端末を他人に触られたときに読める履歴の古さに天井を置きつつ、
         * 見返す用途では気づかない長さにする。
         */
        const val DEFAULT_TIMELINE_RETENTION_DAYS = 90

        /** 配送先 topic を settings に 1 文字列で保持する際の区切り。 */
        private const val TOPIC_SEPARATOR = "\n"

        /**
         * このアプリが読み書きしない設定キー。保存のたびに取り除き、
         * 使われない値が端末に残り続けないようにする。
         */
        private val UNUSED_KEYS = listOf("revokedDeviceIds")

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

/** 全端末共有の blob topic を採番する（§8、§4.3）。 */
fun generateBlobTopic(): String = "peranta-blob-${randomTopicSuffix()}"

/** 端末の安定 ID（ランダム UUID）を生成する。 */
@OptIn(ExperimentalUuidApi::class)
fun generateDeviceId(): String = Uuid.random().toString()
