package to.sava.peranta.android

/**
 * [AndroidNotificationPresenter] が出す通知（ミラー通知とエラーの知らせ）に付ける tag（§3.2）。
 * 添付の転送と通知の再送の進行中に出す通知は固定の通知 ID を tag を付けずに使うため、連番で払い出す
 * 通知 ID がそれと同じ値に届いても、tag が違えば別の通知として扱われる。表示・取り下げ・表示中の照会の
 * すべてをこの tag 付きで行う。
 */
internal const val MIRROR_NOTIFICATION_TAG: String = "peranta-mirror"

/**
 * 出した直後のミラー通知を、OS の一覧に載っていなくても表示中とみなす時間（§4.3.1）。
 * `NotificationManager.notify` が返ってからシステム側で受け付けの処理が走るまでのごく短い間は、
 * 表示中の一覧（`NotificationManager.getActiveNotifications`）に載っていないことがあるため。
 */
internal const val RECENT_MIRROR_POST_WINDOW_MILLIS: Long = 1_000L

/**
 * この端末が直近に出したミラー通知の記録（§4.3.1）。payload.id ごとに出した時刻を持つ。
 * presenter は呼び出しのたびに作られるため、プロセス内で 1 つを共有する。
 * 取り下げ（[forget]）で記録を消し、取り下げたものを表示中とみなさない。
 * [clock] は経過時間を測る単調増加の時計（ミリ秒）。
 */
internal class RecentMirrorPosts(
    private val clock: () -> Long,
    private val windowMillis: Long = RECENT_MIRROR_POST_WINDOW_MILLIS,
) {

    private val postedAt = HashMap<String, Long>()

    /** [payloadId] のミラー通知を出したことを記録する。窓を過ぎた古い記録はここで捨てる。 */
    @Synchronized
    fun recordPosted(payloadId: String) {
        val now = clock()
        postedAt.entries.removeAll { now - it.value >= windowMillis }
        postedAt[payloadId] = now
    }

    /** [payloadId] の記録を消す。ミラー通知を取り下げたときに呼ぶ。 */
    @Synchronized
    fun forget(payloadId: String) {
        postedAt.remove(payloadId)
    }

    /** [payloadId] のミラー通知を、窓の時間内に出したか。 */
    @Synchronized
    fun isRecentlyPosted(payloadId: String): Boolean {
        val at = postedAt[payloadId] ?: return false
        return clock() - at < windowMillis
    }
}

/**
 * 改版を受けて、通知 ID [notificationId] のミラー通知を出し直すか（§4.3.1）。表示中のものだけを出し直す。
 * 表示中とは、OS が表示中として返す tag 付きの一覧（[activeMirrorNotificationIds]）に在るか、
 * この端末が直近に出したもの（[recentlyPosted]）をいう。
 * 失効時刻 [expiresAtEpochMillis] を過ぎたものは表示中でも出し直さない。出し直すと失効までの
 * 残り時間を付けられず、消えるはずの通知が残り続けるため。
 */
internal fun shouldRefreshMirrorNotification(
    notificationId: Int,
    activeMirrorNotificationIds: Set<Int>,
    recentlyPosted: Boolean,
    expiresAtEpochMillis: Long?,
    nowEpochMillis: Long,
): Boolean {
    if (expiresAtEpochMillis != null && expiresAtEpochMillis <= nowEpochMillis) return false
    return recentlyPosted || notificationId in activeMirrorNotificationIds
}

/**
 * 表示中の通知（tag [tag]・ID [id]・チャネル [channelId]）が、旧バージョンが tag を付けずに出した
 * 通知 ID [notificationId] の通知（ミラー通知かエラーの知らせ）か。添付の転送と通知の再送の進行中に
 * 出す通知も同じ ID を tag を付けずに使いうるため、ID に加えて、[AndroidNotificationPresenter] が使う
 * チャネル（[mirrorChannelIds]）かどうかで見分ける。
 */
internal fun isUntaggedMirrorNotification(
    tag: String?,
    id: Int,
    channelId: String?,
    notificationId: Int,
    mirrorChannelIds: Set<String>,
): Boolean = tag == null && id == notificationId && channelId != null && channelId in mirrorChannelIds
