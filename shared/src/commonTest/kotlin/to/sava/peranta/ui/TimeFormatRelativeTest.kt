package to.sava.peranta.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeFormatRelativeTest {

    private val now = 1_000_000_000_000L

    /** 59 秒前は「たった今」の境界内。 */
    @Test
    fun fiftyNineSecondsAgoIsJustNow() {
        assertEquals("たった今", formatRelativeTime(now, now - 59_000L))
    }

    /** ちょうど 60 秒前は「1分前」（分表記）に切り替わる。 */
    @Test
    fun sixtySecondsAgoIsOneMinuteAgo() {
        assertEquals("1分前", formatRelativeTime(now, now - 60_000L))
    }

    /** 59 分前は分表記のまま。 */
    @Test
    fun fiftyNineMinutesAgoStaysInMinutes() {
        assertEquals("59分前", formatRelativeTime(now, now - 59 * 60_000L))
    }

    /** ちょうど 60 分前は「1時間前」（時間表記）に切り替わる。 */
    @Test
    fun sixtyMinutesAgoIsOneHourAgo() {
        assertEquals("1時間前", formatRelativeTime(now, now - 60 * 60_000L))
    }

    /** 23 時間前は時間表記のまま。 */
    @Test
    fun twentyThreeHoursAgoStaysInHours() {
        assertEquals("23時間前", formatRelativeTime(now, now - 23 * 60 * 60_000L))
    }

    /** ちょうど 24 時間前は「1日前」（日表記）に切り替わる。 */
    @Test
    fun twentyFourHoursAgoIsOneDayAgo() {
        assertEquals("1日前", formatRelativeTime(now, now - 24 * 60 * 60_000L))
    }

    /** 未来時刻（端末間の時計ずれ）は「たった今」へ丸める。 */
    @Test
    fun futureTimeIsJustNow() {
        assertEquals("たった今", formatRelativeTime(now, now + 60_000L))
    }
}
