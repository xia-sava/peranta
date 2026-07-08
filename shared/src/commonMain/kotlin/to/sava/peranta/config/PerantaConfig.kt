package to.sava.peranta.config

import to.sava.peranta.filter.FilterMode
import to.sava.peranta.filter.FilterRule

/** サーバホスト名の既定値。 */
const val DEFAULT_HOST: String = "peranta.sava.to"

/**
 * 端末のローカル設定。
 * 共有鍵は base64 表現で保持し、実バイトは復号時に取り出す。
 * port が null のときはスキーム既定ポート（https=443 / http=80）を使う。
 *
 * [deviceId] は端末を一意に識別する安定 ID で、ペイロードの from/to とロスターの参照に使う。
 * [deviceName] は人間向けの表示名で、リネーム可能でありアドレス指定には使わない。
 * [controlTopic] は全端末で共有する presence/ロスター用 topic（§8）で、ペアリングで配布する。
 */
data class PerantaConfig(
    val host: String = DEFAULT_HOST,
    val useTls: Boolean = true,
    val port: Int? = null,
    val accessToken: String? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val sharedKeyBase64: String? = null,
    val keyId: String? = null,
    val receiveTopic: String? = null,
    val controlTopic: String? = null,
    val unifiedPushEndpoint: String? = null,
    val sendEnabled: Boolean = false,
    val smsDirectReceive: Boolean = true,
    val filterMode: FilterMode = FilterMode.DENYLIST,
    val deliveryTopics: List<String> = emptyList(),
    val filterRules: List<FilterRule> = emptyList(),
    val persistSensitiveHistory: Boolean = false,
    val otpSenderPackages: List<String> = emptyList(),
) {
    /** 受信パイプラインを開始できるだけの設定が揃っているか。 */
    val isReadyForReceive: Boolean
        get() = host.isNotBlank() &&
            !accessToken.isNullOrBlank() &&
            !deviceName.isNullOrBlank() &&
            !sharedKeyBase64.isNullOrBlank() &&
            !keyId.isNullOrBlank() &&
            !receiveTopic.isNullOrBlank()

    /**
     * UnifiedPush 経由で受信・復号できるだけの設定が揃っているか。
     * 復号（共有鍵・keyId）と表示名（端末名）に必要な項目のみを見る。
     * 宛先検証に使う deviceId は初回アクセス時に自動生成するため要件に含めない。
     * ntfy への接続はディストリビュータ（ntfy アプリ）が担うため host/token/topic は要件に含めない。
     */
    val isReadyForUnifiedPushReceive: Boolean
        get() = !deviceName.isNullOrBlank() &&
            !sharedKeyBase64.isNullOrBlank() &&
            !keyId.isNullOrBlank()

    /**
     * 送信パイプラインを開始できるだけの設定が揃っているか。
     * 配送先はロスター（control topic）または静的な配送先 topic のどちらかで解決できればよい。
     */
    val isReadyForSend: Boolean
        get() = host.isNotBlank() &&
            !accessToken.isNullOrBlank() &&
            !deviceName.isNullOrBlank() &&
            !sharedKeyBase64.isNullOrBlank() &&
            !keyId.isNullOrBlank() &&
            (deliveryTopics.isNotEmpty() || !controlTopic.isNullOrBlank())
}
