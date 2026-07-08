package to.sava.peranta.config

/** サーバホスト名の既定値。 */
const val DEFAULT_HOST: String = "peranta.sava.to"

/**
 * 端末のローカル設定。
 * 共有鍵は base64 表現で保持し、実バイトは復号時に取り出す。
 * port が null のときはスキーム既定ポート（https=443 / http=80）を使う。
 */
data class PerantaConfig(
    val host: String = DEFAULT_HOST,
    val useTls: Boolean = true,
    val port: Int? = null,
    val accessToken: String? = null,
    val deviceName: String? = null,
    val sharedKeyBase64: String? = null,
    val keyId: String? = null,
    val receiveTopic: String? = null,
) {
    /** 受信パイプラインを開始できるだけの設定が揃っているか。 */
    val isReadyForReceive: Boolean
        get() = host.isNotBlank() &&
            !accessToken.isNullOrBlank() &&
            !deviceName.isNullOrBlank() &&
            !sharedKeyBase64.isNullOrBlank() &&
            !keyId.isNullOrBlank() &&
            !receiveTopic.isNullOrBlank()
}
