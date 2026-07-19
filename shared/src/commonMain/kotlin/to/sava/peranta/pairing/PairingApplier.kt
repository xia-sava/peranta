package to.sava.peranta.pairing

import to.sava.peranta.config.ConfigRepository
import kotlin.io.encoding.Base64

/**
 * 復号済みの [PairingData] をローカル設定へ適用する（§6）。
 * 共有鍵は [ConfigRepository] を介して KeyStore シームに保存し、他項目は settings に保存する。
 * TLS は常に有効として保存する（§16）。
 *
 * 端末名は QR に含まれない。[deviceName] を渡した場合は空文字列でもそのまま適用し、
 * null のときは既存設定を引き継ぐ。共有鍵・keyId が揃い端末名が設定済みなら、
 * 適用後に受信ロールが有効になる（[to.sava.peranta.config.PerantaConfig.isReadyForUnifiedPushReceive]）。
 */
class PairingApplier(private val configRepository: ConfigRepository) {

    fun apply(data: PairingData, deviceName: String? = null) {
        val current = configRepository.load()
        configRepository.save(
            current.copy(
                host = data.host,
                accessToken = data.token,
                keyId = data.keyId,
                sharedKeyBase64 = Base64.encode(data.key),
                useTls = true,
                port = data.port,
                deviceName = deviceName ?: current.deviceName,
                controlTopic = data.controlTopic ?: current.controlTopic,
                blobTopic = data.blobTopic ?: current.blobTopic,
            ),
        )
    }
}
