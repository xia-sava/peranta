package to.sava.peranta.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MirrorNotificationRefreshTest {

    private class FakeClock(var nowMillis: Long = 100_000L) {
        fun advance(millis: Long) {
            nowMillis += millis
        }
    }

    private val clock = FakeClock()
    private val posts = RecentMirrorPosts(clock = { clock.nowMillis })

    private fun shouldRefresh(
        activeIds: Set<Int> = emptySet(),
        recentlyPosted: Boolean = false,
        expiresAt: Long? = null,
        now: Long = NOW_EPOCH_MILLIS,
    ): Boolean = shouldRefreshMirrorNotification(
        notificationId = NOTIFICATION_ID,
        activeMirrorNotificationIds = activeIds,
        recentlyPosted = recentlyPosted,
        expiresAtEpochMillis = expiresAt,
        nowEpochMillis = now,
    )

    /** 出した直後を表示中とみなす窓は、仕様どおり 1 秒（§4.3.1）。 */
    @Test
    fun windowIsOneSecond() {
        assertEquals(1_000L, RECENT_MIRROR_POST_WINDOW_MILLIS)
    }

    /** 出した直後は、窓の時間内なら直近に出したものとみなす。 */
    @Test
    fun postIsRecentWithinWindow() {
        posts.recordPosted(PAYLOAD_ID)
        clock.advance(WINDOW_MILLIS - 1)
        assertTrue(posts.isRecentlyPosted(PAYLOAD_ID))
    }

    /** 窓の時間を過ぎたら、直近に出したものとみなさない。 */
    @Test
    fun postIsNotRecentAfterWindow() {
        posts.recordPosted(PAYLOAD_ID)
        clock.advance(WINDOW_MILLIS)
        assertFalse(posts.isRecentlyPosted(PAYLOAD_ID))
    }

    /** 出していない payload は、直近に出したものとみなさない。 */
    @Test
    fun unrecordedPayloadIsNotRecent() {
        assertFalse(posts.isRecentlyPosted(PAYLOAD_ID))
    }

    /** 取り下げ（forget）たものは、窓の時間内でも直近に出したものとみなさない。 */
    @Test
    fun forgottenPostIsNotRecent() {
        posts.recordPosted(PAYLOAD_ID)
        posts.forget(PAYLOAD_ID)
        assertFalse(posts.isRecentlyPosted(PAYLOAD_ID))
    }

    /** 取り下げは対象の payload の記録だけを消す。 */
    @Test
    fun forgetKeepsOtherPayloads() {
        posts.recordPosted(PAYLOAD_ID)
        posts.recordPosted(OTHER_PAYLOAD_ID)
        posts.forget(OTHER_PAYLOAD_ID)
        assertTrue(posts.isRecentlyPosted(PAYLOAD_ID))
    }

    /** OS が表示中として返す一覧に在れば、出し直す。 */
    @Test
    fun refreshesWhenListedAsActive() {
        assertTrue(shouldRefresh(activeIds = setOf(NOTIFICATION_ID)))
    }

    /** OS の一覧にまだ載っていなくても、直近に出したものなら出し直す。 */
    @Test
    fun refreshesRecentlyPostedEvenIfNotListed() {
        assertTrue(shouldRefresh(recentlyPosted = true))
    }

    /** OS の一覧に無く、直近に出したものでもなければ（払いのけた・タップした・失効した）出し直さない。 */
    @Test
    fun doesNotRefreshWhenNeitherListedNorRecent() {
        assertFalse(shouldRefresh(activeIds = setOf(NOTIFICATION_ID + 1)))
    }

    /** 失効時刻を過ぎたものは、OS の一覧に在っても出し直さない（失効が外れて残り続けるため）。 */
    @Test
    fun doesNotRefreshPastExpiryEvenIfListed() {
        assertFalse(
            shouldRefresh(activeIds = setOf(NOTIFICATION_ID), expiresAt = NOW_EPOCH_MILLIS),
        )
    }

    /** 失効時刻を過ぎたものは、直近に出したものでも出し直さない。 */
    @Test
    fun doesNotRefreshPastExpiryEvenIfRecent() {
        assertFalse(shouldRefresh(recentlyPosted = true, expiresAt = NOW_EPOCH_MILLIS - 1))
    }

    /** 失効時刻の前なら、表示中のものを出し直す。 */
    @Test
    fun refreshesBeforeExpiry() {
        assertTrue(shouldRefresh(activeIds = setOf(NOTIFICATION_ID), expiresAt = NOW_EPOCH_MILLIS + 1))
    }

    /** 取り下げたあとは、OS の一覧に無ければ窓の時間内でも出し直さない。 */
    @Test
    fun doesNotRefreshAfterCancel() {
        posts.recordPosted(PAYLOAD_ID)
        posts.forget(PAYLOAD_ID)
        assertFalse(shouldRefresh(recentlyPosted = posts.isRecentlyPosted(PAYLOAD_ID)))
    }

    /** 窓の時間を過ぎたら、OS の一覧に在るかだけで決まる（一覧に無ければ出し直さない）。 */
    @Test
    fun afterWindowNotListedDoesNotRefresh() {
        posts.recordPosted(PAYLOAD_ID)
        clock.advance(WINDOW_MILLIS)
        assertFalse(shouldRefresh(recentlyPosted = posts.isRecentlyPosted(PAYLOAD_ID)))
    }

    /** 窓の時間を過ぎても、OS の一覧に在れば出し直す。 */
    @Test
    fun afterWindowListedRefreshes() {
        posts.recordPosted(PAYLOAD_ID)
        clock.advance(WINDOW_MILLIS)
        assertTrue(
            shouldRefresh(activeIds = setOf(NOTIFICATION_ID), recentlyPosted = posts.isRecentlyPosted(PAYLOAD_ID)),
        )
    }

    /** tag の無い、ミラー通知のチャネルの同じ ID の通知は、旧バージョンのミラー通知とみなす。 */
    @Test
    fun untaggedNotificationOnMirrorChannelIsLegacyMirror() {
        assertTrue(
            isUntaggedMirrorNotification(
                tag = null,
                id = NOTIFICATION_ID,
                channelId = MIRROR_CHANNEL,
                notificationId = NOTIFICATION_ID,
                mirrorChannelIds = MIRROR_CHANNELS,
            ),
        )
    }

    /** 同じ ID でも、転送・再送のチャネルの tag の無い通知はミラー通知とみなさない。 */
    @Test
    fun untaggedTransferNotificationIsNotMirror() {
        assertFalse(
            isUntaggedMirrorNotification(
                tag = null,
                id = NOTIFICATION_ID,
                channelId = TRANSFER_CHANNEL,
                notificationId = NOTIFICATION_ID,
                mirrorChannelIds = MIRROR_CHANNELS,
            ),
        )
    }

    /** tag の付いた通知は、tag なしの取り下げの対象にしない。 */
    @Test
    fun taggedNotificationIsNotUntaggedMirror() {
        assertFalse(
            isUntaggedMirrorNotification(
                tag = MIRROR_NOTIFICATION_TAG,
                id = NOTIFICATION_ID,
                channelId = MIRROR_CHANNEL,
                notificationId = NOTIFICATION_ID,
                mirrorChannelIds = MIRROR_CHANNELS,
            ),
        )
    }

    /** ID が違う通知は、ミラー通知のチャネルでも対象にしない。 */
    @Test
    fun differentIdIsNotTarget() {
        assertFalse(
            isUntaggedMirrorNotification(
                tag = null,
                id = NOTIFICATION_ID + 1,
                channelId = MIRROR_CHANNEL,
                notificationId = NOTIFICATION_ID,
                mirrorChannelIds = MIRROR_CHANNELS,
            ),
        )
    }

    /** チャネルを持たない通知はミラー通知とみなさない。 */
    @Test
    fun notificationWithoutChannelIsNotMirror() {
        assertFalse(
            isUntaggedMirrorNotification(
                tag = null,
                id = NOTIFICATION_ID,
                channelId = null,
                notificationId = NOTIFICATION_ID,
                mirrorChannelIds = MIRROR_CHANNELS,
            ),
        )
    }

    private companion object {
        const val WINDOW_MILLIS = RECENT_MIRROR_POST_WINDOW_MILLIS
        const val NOW_EPOCH_MILLIS = 1_700_000_000_000L
        const val NOTIFICATION_ID = 4201
        const val PAYLOAD_ID = "p1"
        const val OTHER_PAYLOAD_ID = "p2"
        const val MIRROR_CHANNEL = "peranta-normal"
        const val TRANSFER_CHANNEL = "peranta-attachment-transfer"
        val MIRROR_CHANNELS = setOf("peranta-high", MIRROR_CHANNEL, "peranta-low", "peranta-error")
    }
}
