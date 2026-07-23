package to.sava.peranta.config

import to.sava.peranta.filter.FilterMode
import to.sava.peranta.filter.FilterRule

/** サーバホスト名の既定値。 */
const val DEFAULT_HOST: String = "peranta.example.com"

/**
 * 端末のローカル設定。
 * 共有鍵は base64 表現で保持し、実バイトは復号時に取り出す。
 * port が null のときはスキーム既定ポート（https=443 / http=80）を使う。
 *
 * [deviceId] は端末を一意に識別する安定 ID で、ペイロードの from/to とロスターの参照に使う。
 * [deviceName] は人間向けの表示名で、リネーム可能でありアドレス指定には使わない。
 * [controlTopic] は全端末で共有する presence/ロスター用 topic（§8）で、ペアリングで配布する。
 * [blobTopic] は全端末で共有する画像/ファイル転送用 topic（§8、§4.3）で、ペアリングで配布する。
 * [revokedDeviceIds] は失効させた端末の deviceId 集合で、ロスター解決時に配送先から除外する（§9）。
 * [attachFullTextWhenTruncated] が true のとき、プレビュー予算を超える本文は全文を暗号化 blob として
 * 添付し、受信側で自動展開させる（§4.3）。false なら従来どおり単純にバイト切り詰めする。
 * [timelineRetentionDays] はタイムライン履歴の保持日数（§11）。null は日数による剪定を行わない
 * （既定。既存ユーザーの履歴を黙って消さないため）。端末ローカルの表示設定でありペアリング QR には含めない。
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
    val blobTopic: String? = null,
    val unifiedPushEndpoint: String? = null,
    val sendEnabled: Boolean = false,
    val smsDirectReceive: Boolean = true,
    val filterMode: FilterMode = FilterMode.DENYLIST,
    val deliveryTopics: List<String> = emptyList(),
    val filterRules: List<FilterRule> = emptyList(),
    val persistSensitiveHistory: Boolean = false,
    val otpSenderPackages: List<String> = emptyList(),
    val revokedDeviceIds: Set<String> = emptySet(),
    val attachFullTextWhenTruncated: Boolean = true,
    val timelineRetentionDays: Int? = null,
) {
    /**
     * ペアリング済みか（共有鍵と keyId が揃っているか）。
     * 未ペアリングなら QR 取り込み画面（§10.3）へ誘導する判定に使う。
     */
    val hasSharedKey: Boolean
        get() = !sharedKeyBase64.isNullOrBlank() && !keyId.isNullOrBlank()

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

/** 1 日のミリ秒数。保持日数から [TimelineStore.prune] の経過時間へ変換する際に使う（§11）。 */
private const val MILLIS_PER_DAY: Long = 24L * 60 * 60 * 1000

/**
 * [PerantaConfig.timelineRetentionDays] を [to.sava.peranta.timeline.TimelineStore.prune] に渡す
 * 経過時間（ミリ秒）へ変換する。未設定（日数による剪定なし）なら null。
 */
val PerantaConfig.timelineRetentionMaxAgeMillis: Long?
    get() = timelineRetentionDays?.let { it.toLong() * MILLIS_PER_DAY }
