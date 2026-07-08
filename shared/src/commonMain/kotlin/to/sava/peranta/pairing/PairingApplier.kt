package to.sava.peranta.pairing

import to.sava.peranta.config.ConfigRepository
import kotlin.io.encoding.Base64

/**
 * 復号済みの [PairingData] をローカル設定へ適用する（§6）。
 * 共有鍵は [ConfigRepository] を介して KeyStore シームに保存し、他項目は settings に保存する。
 * 端末名は QR に含まれないため、既存設定を引き継ぐ。共有鍵・keyId が揃うため、
 * 端末名が設定済みなら適用後に受信ロールが有効になる（[to.sava.peranta.config.PerantaConfig.isReadyForUnifiedPushReceive]）。
 */
class PairingApplier(private val configRepository: ConfigRepository) {

    fun apply(data: PairingData) {
        val current = configRepository.load()
        configRepository.save(
            current.copy(
                host = data.host,
                accessToken = data.token,
                keyId = data.keyId,
                sharedKeyBase64 = Base64.encode(data.key),
                useTls = data.tls,
                port = data.port,
                controlTopic = data.controlTopic ?: current.controlTopic,
            ),
        )
    }
}
