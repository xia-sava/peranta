package to.sava.peranta.pairing

import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.crypto.generateKey
import kotlin.io.encoding.Base64

/**
 * 現在の keyId から次の keyId を採番する（§6 の連番運用）。
 * 未設定・非数値・1 未満は "1" にリセットし、正の整数はその +1 を返す。
 */
fun nextKeyId(current: String?): String {
    val parsed = current?.trim()?.toIntOrNull()
    return if (parsed == null || parsed < 1) "1" else (parsed + 1).toString()
}

/**
 * 設定画面（§10.2）とペアリング画面（§10.3）の操作ロジックを Compose から切り離して持つ。
 * 永続化は [ConfigRepository] に委譲し、UI は結果の [PerantaConfig] を受け取って表示だけ行う。
 */
class SettingsController(private val repository: ConfigRepository) {

    /** 現在の設定を読み出す。 */
    fun load(): PerantaConfig = repository.load()

    /** 共有鍵が既に存在するか（「鍵を作る」の破棄警告要否の判定に使う）。 */
    fun hasSharedKey(): Boolean = !repository.load().sharedKeyBase64.isNullOrBlank()

    /**
     * 接続まわりの設定を保存する。空文字の token/deviceName は未設定（null）として扱う。
     * 鍵・keyId・topic などは既存値を引き継ぐ。
     */
    fun saveConnectionSettings(
        host: String,
        accessToken: String?,
        deviceName: String?,
        useTls: Boolean,
        port: Int?,
        persistSensitiveHistory: Boolean,
        attachFullTextWhenTruncated: Boolean,
    ): PerantaConfig {
        val updated = repository.load().copy(
            host = host,
            accessToken = accessToken?.takeIf { it.isNotBlank() },
            deviceName = deviceName?.takeIf { it.isNotBlank() },
            useTls = useTls,
            port = port,
            persistSensitiveHistory = persistSensitiveHistory,
            attachFullTextWhenTruncated = attachFullTextWhenTruncated,
        )
        repository.save(updated)
        return updated
    }

    /**
     * 新しい共有鍵を生成し keyId を採番して保存する（§6 の鍵ローテーション）。
     * 既存鍵の破棄確認は呼び出し側（UI の警告ダイアログ）で済ませる前提。
     */
    fun rotateSharedKey(): PerantaConfig {
        val current = repository.load()
        val updated = current.copy(
            sharedKeyBase64 = Base64.encode(generateKey()),
            keyId = nextKeyId(current.keyId),
        )
        repository.save(updated)
        return updated
    }

    /**
     * 現在の設定から新しい端末を追加するためのペアリング URI を組み立てる（§6）。
     * token・keyId・共有鍵のいずれかが未設定なら null を返す。
     * control topic・blob topic は未設定なら採番して永続化し、全端末で共有する値として配布する（§8、§4.3）。
     */
    fun buildPairingUri(): String? {
        val config = repository.load()
        val token = config.accessToken?.takeIf { it.isNotBlank() } ?: return null
        val keyId = config.keyId?.takeIf { it.isNotBlank() } ?: return null
        val keyBase64 = config.sharedKeyBase64?.takeIf { it.isNotBlank() } ?: return null
        val controlTopic = repository.ensureControlTopic()
        val blobTopic = repository.ensureBlobTopic()
        val data = PairingData(
            host = config.host,
            token = token,
            keyId = keyId,
            key = Base64.decode(keyBase64),
            tls = config.useTls,
            port = config.port,
            controlTopic = controlTopic,
            blobTopic = blobTopic,
        )
        return PairingUri.encode(data)
    }
}
