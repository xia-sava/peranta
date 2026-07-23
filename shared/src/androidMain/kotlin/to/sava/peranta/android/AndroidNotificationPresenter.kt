package to.sava.peranta.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import co.touchlab.kermit.Logger
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.receive.NotificationChannelKind
import to.sava.peranta.receive.NotificationDisplay
import to.sava.peranta.receive.NotificationIdAllocator
import to.sava.peranta.receive.channelKindFor
import to.sava.peranta.receive.displayFor
import to.sava.peranta.shared.R
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ReceivedMessage
import to.sava.peranta.timeline.ReceivedNotification

private val notificationIdAllocatorLock = Any()

@Volatile
private var sharedAllocatorInstance: NotificationIdAllocator? = null

/**
 * プロセス内で共有する通知 ID 割り当て器。全 presenter が同一 SharedPreferences 状態を
 * 単一インスタンス経由で扱うことで、通知 ID の衝突を避ける。
 */
private fun sharedNotificationIdAllocator(context: Context): NotificationIdAllocator =
    sharedAllocatorInstance ?: synchronized(notificationIdAllocatorLock) {
        sharedAllocatorInstance ?: NotificationIdAllocator(
            androidSettings(
                context.applicationContext,
                AndroidNotificationPresenter.PREFS_NOTIFICATION_IDS,
            ),
        ).also { sharedAllocatorInstance = it }
    }

/**
 * 受信通知・受信エラーを Android の [NotificationManager] で表示する（§3.2 / §10.1）。
 * 優先度別のチャネルを用意し、payload.id とローカル通知 ID の対応を保持する。
 */
class AndroidNotificationPresenter(
    private val context: Context,
    private val idAllocator: NotificationIdAllocator = sharedNotificationIdAllocator(context),
    private val log: Logger = Logger.withTag("Notify"),
    private val now: () -> Long = ::nowEpochMillis,
) {

    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    init {
        createChannels()
    }

    /** 受信通知を優先度に応じたチャネルで表示する。表示対象外の payload は何もしない。 */
    fun show(item: ReceivedNotification) {
        val display = displayFor(item) ?: run {
            log.d { "notification skipped (not displayable) id=${item.id}" }
            return
        }
        show(display)
    }

    /** 受信メッセージを NORMAL チャネルで表示する。 */
    fun show(item: ReceivedMessage) {
        show(displayFor(item))
    }

    private fun show(display: NotificationDisplay) {
        val channelId = channelIdFor(channelKindFor(display.priority))
        val notificationId = idAllocator.idFor(display.id)
        val notification = Notification.Builder(context, channelId)
            .setContentTitle(display.title)
            .setContentText(display.body)
            .setStyle(Notification.BigTextStyle().bigText(display.body))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .applyContentIntent()
            .applyExpiry(display)
            .build()
        notify(notificationId, notification)
    }

    /**
     * 表示済みの受信通知を取り下げる（既読同期、§3.4）。
     * payload.id から割り当て済みローカル通知 ID を引いて [NotificationManager] から消す。
     * 対応 ID が無い（未表示）場合も cancel は無害に空振りする。
     */
    fun cancel(payloadId: String) {
        val notificationId = idAllocator.idFor(payloadId)
        manager.cancel(notificationId)
        log.i { "notification cancelled id=$notificationId" }
    }

    /** 受信・復号エラーをローカル通知でも知らせる（§10.1）。 */
    fun showError(item: ErrorItem) {
        val notification = Notification.Builder(context, CHANNEL_ERROR)
            .setContentTitle(ERROR_TITLE)
            .setContentText(item.message)
            .setStyle(Notification.BigTextStyle().bigText(item.message))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .applyContentIntent()
            .build()
        notify(idAllocator.idFor(item.id), notification)
    }

    /** タップでアプリ本体を開く PendingIntent を付ける。起動 Intent を引けない場合は付けない。 */
    private fun Notification.Builder.applyContentIntent(): Notification.Builder {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: run {
                log.w { "launch intent not found; notification tap will do nothing" }
                return this
            }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return setContentIntent(pendingIntent)
    }

    private fun Notification.Builder.applyExpiry(display: NotificationDisplay): Notification.Builder {
        val expiresAt = display.expiresAtEpochMillis ?: return this
        val remaining = expiresAt - now()
        if (remaining > 0) {
            setTimeoutAfter(remaining)
        }
        return this
    }

    private fun notify(notificationId: Int, notification: Notification) {
        if (!isPostNotificationsGranted()) {
            log.w { "POST_NOTIFICATIONS not granted; notification id=$notificationId not shown" }
            return
        }
        manager.notify(notificationId, notification)
        log.i { "notification shown id=$notificationId" }
    }

    private fun isPostNotificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun createChannels() {
        listOf(
            NotificationChannel(CHANNEL_HIGH, "重要な通知", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CHANNEL_NORMAL, "通常の通知", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_LOW, "控えめな通知", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CHANNEL_ERROR, "受信エラー", NotificationManager.IMPORTANCE_DEFAULT),
        ).forEach { manager.createNotificationChannel(it) }
    }

    private fun channelIdFor(kind: NotificationChannelKind): String = when (kind) {
        NotificationChannelKind.HIGH -> CHANNEL_HIGH
        NotificationChannelKind.NORMAL -> CHANNEL_NORMAL
        NotificationChannelKind.LOW -> CHANNEL_LOW
    }

    companion object {
        private const val CHANNEL_HIGH = "peranta-high"
        private const val CHANNEL_NORMAL = "peranta-normal"
        private const val CHANNEL_LOW = "peranta-low"
        private const val CHANNEL_ERROR = "peranta-error"
        private const val ERROR_TITLE = "Peranta 受信エラー"

        /** 通知 ID 対応表を保持する SharedPreferences 名。 */
        const val PREFS_NOTIFICATION_IDS = "peranta-notification-ids"
    }
}
