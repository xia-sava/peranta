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

    /** 設定サブ画面（受信のセットアップ・動作チェック・接続設定と暗号キーの取り込み）3つとも、
     * 設定画面から入るときは遷移元として設定を覚える。 */
    @Test
    fun enteringSubScreensFromSettingsRecordsSettingsAsOrigin() {
        subScreenDestinations.forEach { target ->
            val nav = shellNavigate(from = ShellDestination.Settings, to = target)
            assertEquals(ShellDestination.Settings, nav.subScreenOrigin, "設定→$target は遷移元に設定を覚えるべき")
        }
    }

    /** 設定サブ画面3つとも、タイムラインの警告バナーから入るときは遷移元としてタイムラインを覚える。 */
    @Test
    fun enteringSubScreensFromTimelineRecordsTimelineAsOrigin() {
        subScreenDestinations.forEach { target ->
            val nav = shellNavigate(from = ShellDestination.Timeline, to = target)
            assertEquals(ShellDestination.Timeline, nav.subScreenOrigin, "タイムライン→$target は遷移元にタイムラインを覚えるべき")
        }
    }

    /** 設定サブ画面以外への遷移では遷移元の記憶を消す（1段のみ・入れ子なし）。 */
    @Test
    fun enteringNonSubScreenClearsOrigin() {
        val nav = shellNavigate(from = ShellDestination.HealthCheck, to = ShellDestination.Settings)
        assertNull(nav.subScreenOrigin)
    }

    /** 設定サブ画面での「戻る」は、設定から開いていれば設定へ戻す。 */
    @Test
    fun returnDestinationGoesToSettingsWhenOriginIsSettings() {
        subScreenDestinations.forEach { current ->
            assertEquals(
                ShellDestination.Settings,
                shellReturnDestination(current, ShellDestination.Settings),
                "$current の戻るは設定起点なら設定へ戻すべき",
            )
        }
    }

    /** 設定サブ画面での「戻る」は、タイムラインの警告バナーから開いていればタイムラインへ戻す。 */
    @Test
    fun returnDestinationGoesToTimelineWhenOriginIsTimeline() {
        subScreenDestinations.forEach { current ->
            assertEquals(
                ShellDestination.Timeline,
                shellReturnDestination(current, ShellDestination.Timeline),
                "$current の戻るはタイムライン起点ならタイムラインへ戻すべき",
            )
        }
    }

    /** 設定サブ画面での「戻る」は、遷移元の記憶が無ければ（プロセス再生成での復元等）タイムラインへ戻す。 */
    @Test
    fun returnDestinationGoesToTimelineWhenOriginIsMissing() {
        subScreenDestinations.forEach { current ->
            assertEquals(ShellDestination.Timeline, shellReturnDestination(current, null))
        }
    }

    /** 設定・アプリフィルタからの「戻る」は、遷移元の記憶に依らず現状どおりタイムラインへ戻す。 */
    @Test
    fun returnDestinationForSettingsAndAppFilterAlwaysGoesToTimeline() {
        listOf(ShellDestination.Settings, ShellDestination.AppFilter).forEach { current ->
            listOf(ShellDestination.Settings, ShellDestination.Timeline, null).forEach { origin ->
                assertEquals(ShellDestination.Timeline, shellReturnDestination(current, origin))
            }
        }
    }

    private companion object {
        /** 遷移元を1段だけ覚えて戻る対象の設定サブ画面（§10.0）。 */
        val subScreenDestinations = listOf(
            ShellDestination.ReceiveSetup,
            ShellDestination.HealthCheck,
            ShellDestination.PairingImport,
        )
    }
}
