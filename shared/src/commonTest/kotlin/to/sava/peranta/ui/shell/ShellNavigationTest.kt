package to.sava.peranta.ui.shell

import to.sava.peranta.ui.setup.ReceiveSetupSteps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    /** 未達が無ければ警告バナーの誘導先は無い（バナーを出さない）。 */
    @Test
    fun bannerTargetAbsentWhenNoUnmet() {
        assertNull(setupBannerTarget(emptySet()))
    }

    /** 未達がすべて受信経路系なら受信のセットアップへ誘導する。 */
    @Test
    fun bannerTargetsReceiveSetupWhenAllReceivePath() {
        val ids = setOf(ReceiveSetupSteps.UNIFIED_PUSH_ID, ReceiveSetupSteps.NTFY_BATTERY_ID)
        assertEquals(ShellDestination.ReceiveSetup, setupBannerTarget(ids))
    }

    /** 受信経路系と権限系が混在するなら動作チェックへ誘導する。 */
    @Test
    fun bannerTargetsHealthCheckWhenMixed() {
        val ids = setOf(ReceiveSetupSteps.UNIFIED_PUSH_ID, "nls")
        assertEquals(ShellDestination.HealthCheck, setupBannerTarget(ids))
    }

    /** 未達が受信経路系以外だけでも動作チェックへ誘導する。 */
    @Test
    fun bannerTargetsHealthCheckWhenNoReceivePath() {
        val ids = setOf("nls", "self-battery")
        assertEquals(ShellDestination.HealthCheck, setupBannerTarget(ids))
    }
}
