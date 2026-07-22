package to.sava.peranta.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellNavigationTest {

    /** 設定から他画面へ移るときは、遷移先に依らず設定反映を通す（§10.2）。 */
    @Test
    fun leavingSettingsReflectsRegardlessOfTarget() {
        ShellDestination.entries
            .filter { it != ShellDestination.Settings }
            .forEach { target ->
                val nav = shellNavigate(from = ShellDestination.Settings, to = target)
                assertEquals(target, nav.destination)
                assertTrue(nav.reflectSettings, "設定→$target は設定反映を通すべき")
            }
    }

    /** 設定以外からの遷移は設定反映を通さない。 */
    @Test
    fun leavingNonSettingsDoesNotReflect() {
        val nav = shellNavigate(from = ShellDestination.AppFilter, to = ShellDestination.Timeline)
        assertEquals(ShellDestination.Timeline, nav.destination)
        assertFalse(nav.reflectSettings)
    }

    /** 設定へ入る遷移は設定反映を通さない（離れるときにのみ通す）。 */
    @Test
    fun enteringSettingsDoesNotReflect() {
        val nav = shellNavigate(from = ShellDestination.Timeline, to = ShellDestination.Settings)
        assertFalse(nav.reflectSettings)
    }

    /** 設定に留まる（自己遷移）は設定反映を通さない。 */
    @Test
    fun stayingOnSettingsDoesNotReflect() {
        val nav = shellNavigate(from = ShellDestination.Settings, to = ShellDestination.Settings)
        assertFalse(nav.reflectSettings)
    }

    /** QR 取り込みから他画面へ移るときは、遷移先に依らず設定反映を通す（§10.2）。 */
    @Test
    fun leavingPairingImportReflectsRegardlessOfTarget() {
        ShellDestination.entries
            .filter { it != ShellDestination.PairingImport }
            .forEach { target ->
                val nav = shellNavigate(from = ShellDestination.PairingImport, to = target)
                assertEquals(target, nav.destination)
                assertTrue(nav.reflectSettings, "QR取り込み→$target は設定反映を通すべき")
            }
    }

    /** 同一 destination への遷移（自己遷移）は設定反映を通さない。 */
    @Test
    fun stayingOnTimelineDoesNotReflect() {
        val nav = shellNavigate(from = ShellDestination.Timeline, to = ShellDestination.Timeline)
        assertFalse(nav.reflectSettings)
    }
}
