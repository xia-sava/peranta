package to.sava.peranta.platform

/**
 * 実行中のプラットフォームが持つ能力の申告。ロール（送信/受信/設定元等）とは独立に、
 * そのプラットフォームとして何ができるかだけを表す。
 */
data class PlatformCapabilities(
    /** 他アプリの通知を捕捉できるか（NotificationListenerService）。 */
    val canCaptureNotifications: Boolean,
    /** SMS を直接受信できるか（RECEIVE_SMS）。 */
    val canReceiveSms: Boolean,
    /** 受信経路が UnifiedPush か。true なら UnifiedPush、false なら WebSocket 直購読。 */
    val usesUnifiedPush: Boolean,
    /** 通知の表示に POST_NOTIFICATIONS 権限を要するか。 */
    val requiresPostNotificationsPermission: Boolean,
    /** OS 起動時に自動起動する仕組みを持つか。 */
    val supportsAutoStart: Boolean,
    /**
     * 通知の本文を余さず捕捉するのに、コンパニオン機器としての登録を要するか。
     * Android 15 以降は登録の無いアプリの通知リスナーへ本文が渡らない通知がある。
     */
    val requiresCompanionAssociation: Boolean = false,
)

/** 実行中のプラットフォームの [PlatformCapabilities] を返す。 */
expect fun platformCapabilities(): PlatformCapabilities
