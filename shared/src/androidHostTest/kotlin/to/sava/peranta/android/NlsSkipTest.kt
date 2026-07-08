package to.sava.peranta.android

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NlsSkipTest {

    private fun skip(
        packageName: String,
        defaultSmsPackage: String? = "com.android.messaging",
        isSmsDuplicate: Boolean = false,
        isOngoing: Boolean = false,
        isGroupSummary: Boolean = false,
    ): Boolean = shouldSkipNotification(
        packageName = packageName,
        defaultSmsPackage = defaultSmsPackage,
        isSmsDuplicate = isSmsDuplicate,
        isOngoing = isOngoing,
        isGroupSummary = isGroupSummary,
    )

    /** 自分自身の通知は常に落とす（転送ループ防止）。 */
    @Test
    fun selfNotificationIsAlwaysSkipped() {
        assertTrue(skip(SELF_PACKAGE))
    }

    /** 進行中通知は落とす（音楽・ダウンロード等の常駐通知を転送しない）。 */
    @Test
    fun ongoingNotificationIsSkipped() {
        assertTrue(skip("com.example.music", isOngoing = true))
    }

    /** グループ要約は落とす（個別通知と二重に転送しない）。 */
    @Test
    fun groupSummaryNotificationIsSkipped() {
        assertTrue(skip("com.example.mail", isGroupSummary = true))
    }

    /** 既定 SMS アプリからの重複通知は落とす（直接受信済みと二重転送しない）。 */
    @Test
    fun duplicateSmsAppNotificationIsSkipped() {
        assertTrue(skip("com.android.messaging", isSmsDuplicate = true))
    }

    /** SMS アプリでも重複でなければ落とさない。 */
    @Test
    fun nonDuplicateSmsAppNotificationIsForwarded() {
        assertFalse(skip("com.android.messaging", isSmsDuplicate = false))
    }

    /** SMS アプリ以外は重複判定に関わらず落とさない。 */
    @Test
    fun otherAppNotificationIsForwarded() {
        assertFalse(skip("com.example.bank", isSmsDuplicate = true))
    }
}
