package to.sava.peranta.pairing

import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.config.PerantaConfig
import kotlin.io.encoding.Base64

/**
 * 復号済みの [PairingData] をローカル設定へ適用する（§6）。
 * 共有鍵とアクセストークンは [ConfigRepository] を介して SecretStore シームに保存し、
 * 他項目は settings に保存する。
 * TLS には触れない（有効/無効はビルド種別で決まる、§16）。
 *
 * 端末名は QR に含まれない。[deviceName] を渡した場合は空文字列でもそのまま適用し、
 * null のときは既存設定を引き継ぐ。共有鍵・keyId が揃い端末名が設定済みなら、
 * 適用後に受信ロールが有効になる（[to.sava.peranta.config.PerantaConfig.isReadyForUnifiedPushReceive]）。
 */
class PairingApplier(private val configRepository: ConfigRepository) {

    /** [data] を設定へ適用し、保存した設定を返す。 */
    fun apply(data: PairingData, deviceName: String? = null): PerantaConfig {
        val current = configRepository.load()
        val applied = current.copy(
            host = data.host,
            accessToken = data.token,
            keyId = data.keyId,
            sharedKeyBase64 = Base64.encode(data.key),
            port = data.port,
            deviceName = deviceName ?: current.deviceName,
            controlTopic = data.controlTopic ?: current.controlTopic,
            blobTopic = data.blobTopic ?: current.blobTopic,
        )
        configRepository.save(applied)
        return applied
    }
}
