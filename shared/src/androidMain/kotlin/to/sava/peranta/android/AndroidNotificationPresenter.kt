package to.sava.peranta.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import co.touchlab.kermit.Logger
import to.sava.peranta.model.NotificationVisibility
import to.sava.peranta.model.SwipeBehavior
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.receive.DISMISS_SOURCE_ACTION_LABEL
import to.sava.peranta.receive.NotificationChannelKind
import to.sava.peranta.receive.NotificationDisplay
import to.sava.peranta.receive.NotificationIdAllocator
import to.sava.peranta.receive.OPEN_URL_ACTION_LABEL
import to.sava.peranta.receive.channelKindFor
import to.sava.peranta.shared.R

/**
 * プロセス内で共有する、直近に出したミラー通知の記録。presenter は呼び出しのたびに作られるため、
 * 取り下げ（[AndroidNotificationPresenter.cancel]）と出し直し（[AndroidNotificationPresenter.refresh]）が
 * 同じ記録を見るよう、インスタンスには持たない。
 */
private val sharedRecentMirrorPosts = RecentMirrorPosts(clock = SystemClock::elapsedRealtime)

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
 * この presenter が出す通知（ミラー通知とエラーの知らせ）にはすべて [MIRROR_NOTIFICATION_TAG] を付け、
 * 取り下げと表示中の照会も同じ tag で行う。
 */
class AndroidNotificationPresenter(
    private val context: Context,
    private val idAllocator: NotificationIdAllocator = sharedNotificationIdAllocator(context),
    private val log: Logger = Logger.withTag("Notify"),
    private val now: () -> Long = ::nowEpochMillis,
) {

    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private val recentPosts: RecentMirrorPosts = sharedRecentMirrorPosts

    init {
        createChannels()
    }

    /**
     * [display] を、種別と優先度に応じたチャネル（[channelKindFor]）で表示する。
     * 出したことを記録し、直後に届いた改版の出し直し（[refresh]）で表示中とみなせるようにする。
     * 記録は出す前に付け、出せなかったら消す。出している最中に別のスレッドで取り下げ（[cancel]）が
     * 走っても、取り下げたものの記録が後から残らないようにするため。
     */
    fun show(display: NotificationDisplay) {
        val notificationId = idAllocator.idFor(display.id)
        recentPosts.recordPosted(display.id)
        if (!post(display, notificationId)) recentPosts.forget(display.id)
    }

    /**
     * 表示中の通知を、音も振動も無しに [display] の内容で出し直す（§4.3.1）。
     * 表示中でなければ何もしない。払いのけた・タップした・取り下げた・失効した通知は戻さない。
     * ただし出してから [RECENT_MIRROR_POST_WINDOW_MILLIS] の間は表示中とみなすため、その間に払いのけた・
     * タップした通知は戻りうる。表示中かは [shouldRefreshMirrorNotification] で決め、通知 ID は新たに
     * 割り当てずに引く。
     * [image] は本文画像、[senderIcon] は送信者アイコンで、渡さなければ載せない。
     */
    fun refresh(display: NotificationDisplay, image: Bitmap? = null, senderIcon: Bitmap? = null) {
        val notificationId = idAllocator.existingIdFor(display.id) ?: run {
            log.d { "notification refresh skipped (never shown) payload=${display.id}" }
            return
        }
        val showing = shouldRefreshMirrorNotification(
            notificationId = notificationId,
            activeMirrorNotificationIds = activeMirrorNotificationIds(),
            recentlyPosted = recentPosts.isRecentlyPosted(display.id),
            expiresAtEpochMillis = display.expiresAtEpochMillis,
            nowEpochMillis = now(),
        )
        if (!showing) {
            log.d { "notification refresh skipped (not showing) id=$notificationId" }
            return
        }
        post(display, notificationId, image, senderIcon, silent = true)
    }

    /**
     * [display] を通知 ID [notificationId] で出す。[silent] なら同じ ID の通知の差し替えで音も振動も
     * 鳴らさない（[Notification.Builder.setOnlyAlertOnce]）。通知の時刻はアイテムの時刻に固定し、
     * 出し直しで通知シェードの並びが動かないようにする。通知を出せたら true。
     */
    private fun post(
        display: NotificationDisplay,
        notificationId: Int,
        image: Bitmap? = null,
        senderIcon: Bitmap? = null,
        silent: Boolean = false,
    ): Boolean {
        val channelId = channelIdFor(channelKindFor(display))
        val notification = Notification.Builder(context, channelId)
            .setContentTitle(display.title)
            .setContentText(display.body)
            .setSubText(display.source)
            .setWhen(display.timestampEpochMillis)
            .setStyle(styleFor(display, image))
            .setLargeIcon(senderIcon?.let { Icon.createWithBitmap(it) })
            .setOnlyAlertOnce(silent)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .applyLockScreenVisibility(display, channelId)
            .applyContentIntent(notificationId, display.id)
            .applyExpiry(display)
            .applySwipeBehavior(notificationId, display)
            .applyDismissSourceAction(notificationId, display)
            .applyOpenUrlAction(display)
            .build()
        return notify(notificationId, notification)
    }

    /**
     * 元通知のロック画面可視性を引き継ぐ（§3.2）。伏せる指定（[NotificationVisibility.PRIVATE]）の
     * ときは発信元だけを載せた代替を添え、伏せられた状態でも何からの通知かは判るようにする。
     */
    private fun Notification.Builder.applyLockScreenVisibility(
        display: NotificationDisplay,
        channelId: String,
    ): Notification.Builder =
        setVisibility(androidVisibilityOf(display.visibility)).also {
            if (display.visibility == NotificationVisibility.PRIVATE) {
                it.setPublicVersion(redactedNotification(display, channelId))
            }
        }

    /** ロック画面で本文を伏せるときに代わりに出す通知。発信元だけを載せ、件名も本文も持たない。 */
    private fun redactedNotification(display: NotificationDisplay, channelId: String): Notification =
        Notification.Builder(context, channelId)
            .setContentTitle(display.source ?: REDACTED_TITLE)
            .setSmallIcon(R.drawable.ic_notification)
            .build()

    /** 画像が届いていれば大きな画像で、無ければ本文の全文表示で組む。 */
    private fun styleFor(display: NotificationDisplay, image: Bitmap?): Notification.Style =
        if (image == null) {
            Notification.BigTextStyle().bigText(display.body)
        } else {
            Notification.BigPictureStyle().bigPicture(image).setSummaryText(display.body)
        }

    /**
     * 表示済みの受信通知を取り下げる（既読同期、§3.4）。
     * payload.id から割り当て済みローカル通知 ID を引いて [NotificationManager] から消し、直近に出した
     * 記録（[RecentMirrorPosts]）も消す。通知 ID が割り当てられていなければ、表示していないので何もしない。
     * 旧バージョンが tag を付けずに出した通知が同じ ID で残っていれば、それも取り下げる。
     */
    fun cancel(payloadId: String) {
        recentPosts.forget(payloadId)
        val notificationId = idAllocator.existingIdFor(payloadId) ?: run {
            log.d { "notification cancel skipped (never shown) payload=$payloadId" }
            return
        }
        manager.cancel(MIRROR_NOTIFICATION_TAG, notificationId)
        cancelUntaggedMirrorNotification(notificationId)
        log.i { "notification cancelled id=$notificationId" }
    }

    /**
     * 旧バージョンが tag を付けずに出した通知が [notificationId] で表示中なら取り下げる。
     * 添付の転送と通知の再送の進行中に出す通知も固定の ID を tag を付けずに使うため、ID だけで取り下げずに
     * この presenter のチャネルのものに限る（[isUntaggedMirrorNotification]）。
     * 表示中の照会に失敗しても、呼び出し元の処理（tag 付きの取り下げに続く既読同期の送信など）は止めない。
     */
    private fun cancelUntaggedMirrorNotification(notificationId: Int) {
        val shown = try {
            manager.activeNotifications.any {
                isUntaggedMirrorNotification(
                    tag = it.tag,
                    id = it.id,
                    channelId = it.notification.channelId,
                    notificationId = notificationId,
                    mirrorChannelIds = MIRROR_CHANNEL_IDS,
                )
            }
        } catch (error: Exception) {
            log.w { "active notification lookup failed id=$notificationId (${error::class.simpleName})" }
            return
        }
        if (!shown) return
        manager.cancel(notificationId)
        log.i { "untagged notification cancelled id=$notificationId" }
    }

    /** OS が表示中として返す一覧のうち、[MIRROR_NOTIFICATION_TAG] が付いたものの通知 ID。 */
    private fun activeMirrorNotificationIds(): Set<Int> =
        manager.activeNotifications
            .filter { it.tag == MIRROR_NOTIFICATION_TAG }
            .mapTo(HashSet()) { it.id }

    /**
     * タップでアプリ本体を開き、タイムラインの [itemId] アイテムまでスクロールさせる PendingIntent を
     * 付ける（§3.2）。起動 Intent を引けない場合は付けない。
     * [requestCode] には通知ごとに異なる値（[NotificationIdAllocator.idFor] が払い出す通知 ID）を渡す。
     * 起動 Intent は action / category が全通知で共通のため、requestCode を固定すると extra（[itemId]）
     * だけが異なる PendingIntent が [PendingIntent.filterEquals] 上同一視され、後着の表示が先着の
     * PendingIntent を上書きして両者とも最後にタップされた通知の extra でしか開かなくなる。
     */
    private fun Notification.Builder.applyContentIntent(requestCode: Int, itemId: String): Notification.Builder {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: run {
                log.w { "launch intent not found; notification tap will do nothing" }
                return this
            }
        intent.putExtra(EXTRA_SCROLL_ITEM_ID, itemId)
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
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

    /**
     * 送信端末が「払いのけたら元通知も消す」と指示した通知（§3.3 / §7）に、スワイプで発火する
     * [Notification.Builder.setDeleteIntent] を付ける。指示が無ければ付けず、払いのけはこの端末の
     * 表示を消すだけになる。
     * `NotificationManager.cancel` による取り下げ（既読同期・出し直し）ではこの intent は発火しない。
     * [requestCode] は他の [PendingIntent] と同じ通知 ID でよい（区別は Intent の action が持つ）。
     */
    private fun Notification.Builder.applySwipeBehavior(
        requestCode: Int,
        display: NotificationDisplay,
    ): Notification.Builder {
        if (display.swipeBehavior != SwipeBehavior.DISMISS_SOURCE) return this
        val notificationKey = display.notificationKey ?: return this
        return setDeleteIntent(
            PendingIntent.getBroadcast(
                context,
                requestCode,
                NotificationDismissReceiver.swipeIntent(context, notificationKey),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
    }

    /**
     * 元通知を指せる通知に「送信元の通知を消す」アクションを付ける（§3.2 / §3.4）。
     * 押すと [NotificationDismissReceiver] が既読同期を発火し、元端末と他の受信端末から消える。
     * 対応づいていない SMS・受信メッセージは対象を指せないため付けない。
     * [requestCode] は「開く」やタップと同じ理由で通知ごとに異なる値を渡す。
     */
    private fun Notification.Builder.applyDismissSourceAction(
        requestCode: Int,
        display: NotificationDisplay,
    ): Notification.Builder {
        val notificationKey = display.notificationKey ?: return this
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            NotificationDismissReceiver.intent(context, notificationKey, display.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val icon = Icon.createWithResource(context, R.drawable.ic_notification)
        return addAction(Notification.Action.Builder(icon, DISMISS_SOURCE_ACTION_LABEL, pendingIntent).build())
    }

    /** 本文から URL が抽出できたとき、ACTION_VIEW で開く「開く」アクションを 1 個付ける（§3.2）。 */
    private fun Notification.Builder.applyOpenUrlAction(display: NotificationDisplay): Notification.Builder {
        val url = display.openUrl ?: return this
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val icon = Icon.createWithResource(context, R.drawable.ic_notification)
        return addAction(Notification.Action.Builder(icon, OPEN_URL_ACTION_LABEL, pendingIntent).build())
    }

    /** [notification] を [MIRROR_NOTIFICATION_TAG] 付きで出す。通知の権限が無く出せなければ false。 */
    private fun notify(notificationId: Int, notification: Notification): Boolean {
        if (!isPostNotificationsGranted()) {
            log.w { "POST_NOTIFICATIONS not granted; notification id=$notificationId not shown" }
            return false
        }
        manager.notify(MIRROR_NOTIFICATION_TAG, notificationId, notification)
        log.i { "notification shown id=$notificationId" }
        return true
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
        NotificationChannelKind.ERROR -> CHANNEL_ERROR
    }

    companion object {
        private const val CHANNEL_HIGH = "peranta-high"
        private const val CHANNEL_NORMAL = "peranta-normal"
        private const val CHANNEL_LOW = "peranta-low"
        private const val CHANNEL_ERROR = "peranta-error"

        /** この presenter が通知を出すチャネル。tag の無い通知が旧バージョンの出したものかを見分けるのに使う。 */
        private val MIRROR_CHANNEL_IDS = setOf(CHANNEL_HIGH, CHANNEL_NORMAL, CHANNEL_LOW, CHANNEL_ERROR)

        /** 発信元が判らない通知をロック画面で伏せるときの代替の件名。 */
        private const val REDACTED_TITLE = "通知が届いています"

        /** 通知 ID 対応表を保持する SharedPreferences 名。 */
        const val PREFS_NOTIFICATION_IDS = "peranta-notification-ids"
    }
}
