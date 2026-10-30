package to.sava.peranta.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NlsSkipTest {

    private fun reason(
        packageName: String,
        defaultSmsPackage: String? = "com.android.messaging",
        isSmsDuplicate: Boolean = false,
        isOngoing: Boolean = false,
        isGroupSummary: Boolean = false,
        isCrossProfile: Boolean = false,
        forwardWorkProfile: Boolean = false,
    ): NotificationSkipReason? = notificationSkipReason(
        packageName = packageName,
        defaultSmsPackage = defaultSmsPackage,
        isSmsDuplicate = isSmsDuplicate,
        isOngoing = isOngoing,
        isGroupSummary = isGroupSummary,
        isCrossProfile = isCrossProfile,
        forwardWorkProfile = forwardWorkProfile,
    )

    /** 自分自身の通知は常に落とす（転送ループ防止）。 */
    @Test
    fun selfNotificationIsAlwaysSkipped() {
        assertEquals(NotificationSkipReason.SELF, reason(SELF_PACKAGE))
    }

    /** 進行中通知は落とす（音楽・ダウンロード等の常駐通知を転送しない）。 */
    @Test
    fun ongoingNotificationIsSkipped() {
        assertEquals(NotificationSkipReason.ONGOING, reason("com.example.music", isOngoing = true))
    }

    /** グループ要約は落とす（個別通知と二重に転送しない）。 */
    @Test
    fun groupSummaryNotificationIsSkipped() {
        assertEquals(NotificationSkipReason.GROUP_SUMMARY, reason("com.example.mail", isGroupSummary = true))
    }

    /** 既定 SMS アプリからの重複通知は、SMS の元通知として区別できる理由で落とす。 */
    @Test
    fun duplicateSmsAppNotificationIsSkippedAsSmsDuplicate() {
        assertEquals(
            NotificationSkipReason.SMS_DUPLICATE,
            reason("com.android.messaging", isSmsDuplicate = true),
        )
    }

    /** SMS アプリでも重複でなければ落とさない。 */
    @Test
    fun nonDuplicateSmsAppNotificationIsForwarded() {
        assertNull(reason("com.android.messaging", isSmsDuplicate = false))
    }

    /** SMS アプリ以外は重複判定に関わらず落とさない。 */
    @Test
    fun otherAppNotificationIsForwarded() {
        assertNull(reason("com.example.bank", isSmsDuplicate = true))
    }

    /** 仕事用プロファイルの通知は、その転送を有効にしていなければ落とす（§3.1）。 */
    @Test
    fun workProfileNotificationIsSkippedByDefault() {
        assertEquals(
            NotificationSkipReason.WORK_PROFILE,
            reason("com.example.mail", isCrossProfile = true),
        )
    }

    /** 仕事用プロファイルの転送を有効にすれば、個人プロファイルと同じ扱いになる。 */
    @Test
    fun workProfileNotificationIsForwardedWhenEnabled() {
        assertNull(reason("com.example.mail", isCrossProfile = true, forwardWorkProfile = true))
    }

    /** 個人プロファイルの通知は、仕事用プロファイルの設定に影響されない。 */
    @Test
    fun personalProfileNotificationIsUnaffectedByTheWorkProfileSetting() {
        assertNull(reason("com.example.mail"))
        assertNull(reason("com.example.mail", forwardWorkProfile = true))
    }

    private fun hold(
        packageName: String = "com.android.messaging",
        isDirectSmsReceiving: Boolean = true,
        alreadyHeld: Boolean = false,
    ): Boolean = shouldHoldForDirectSms(
        packageName = packageName,
        defaultSmsPackage = "com.android.messaging",
        isDirectSmsReceiving = isDirectSmsReceiving,
        alreadyHeld = alreadyHeld,
    )

    /** 直接受信が有効なら、既定 SMS アプリの通知は直接受信を待って転送を遅らせる（§3.1）。 */
    @Test
    fun smsAppNotificationIsHeldWhileReceivingSmsDirectly() {
        assertTrue(hold())
    }

    /** 遅らせるかどうかの判定で、遅らせない条件ごとの結果。 */
    @Test
    fun notificationIsNotHeldOtherwise() {
        listOf(
            "既定 SMS アプリ以外" to hold(packageName = "com.example.bank"),
            "直接受信が無効" to hold(isDirectSmsReceiving = false),
            "遅らせた後の判定し直し" to hold(alreadyHeld = true),
        ).forEach { (case, held) -> assertFalse(held, case) }
    }
}
