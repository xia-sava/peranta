package to.sava.peranta.autostart

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import to.sava.peranta.DesktopSelfTest
import to.sava.peranta.net.SelfTestResult
import to.sava.peranta.net.SelfTestStatus
import to.sava.peranta.ui.HealthCheckState
import to.sava.peranta.ui.setup.ReceiveSetupSteps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopHealthCheckerTest {

    private class FakeRegistry(var command: String? = null, var registerSucceeds: Boolean = true) : AutoStartRegistry {
        override fun currentCommand(): String? = command
        override fun register(command: String): Boolean {
            if (!registerSucceeds) return false
            this.command = command
            return true
        }

        override fun unregister() {
            command = null
        }
    }

    /** テスト用の受信機フェイク。probe 実行の代わりに任意の状態を差し込める。 */
    private class FakeDesktopSelfTest(initial: SelfTestStatus = SelfTestStatus.NotRun) : DesktopSelfTest {
        private val state = MutableStateFlow(initial)
        override val selfTestStatus: StateFlow<SelfTestStatus> = state
        var startCount: Int = 0
            private set

        override fun startSelfTest() {
            startCount++
        }
    }

    private val appPath = "C:\\Program Files\\Peranta\\Peranta.exe"

    /** 未登録項目の onFix は、レジストリへの登録が成功すれば例外を送出しない。 */
    @Test
    fun onFixSucceedsWhenRegistrationSucceeds() = runTest {
        val registry = FakeRegistry()
        val manager = AutoStartManager(registry, appPath)
        val checker = DesktopHealthChecker(manager)

        val item = checker.check().single { it.id == "autostart" }
        item.onFix?.invoke()

        assertTrue(manager.isEnabled())
    }

    /** 未登録項目の onFix は、レジストリへの登録が失敗すると例外を送出して呼び出し元へ失敗を伝える。 */
    @Test
    fun onFixThrowsWhenRegistrationFails() = runTest {
        val registry = FakeRegistry(registerSucceeds = false)
        val manager = AutoStartManager(registry, appPath)
        val checker = DesktopHealthChecker(manager)

        val item = checker.check().single { it.id == "autostart" }
        assertFailsWith<IllegalStateException> { item.onFix?.invoke() }
    }

    private fun checkerWithSelfTest(selfTest: () -> DesktopSelfTest?): DesktopHealthChecker =
        DesktopHealthChecker(AutoStartManager(FakeRegistry(), appPath), selfTest)

    /** 受信機が未稼働（プロバイダが null）なら INFO で理由を示し、実行ボタンは持たない。 */
    @Test
    fun selfTestItemIsInfoWithoutFixButtonWhenReceiverUnavailable() = runTest {
        val checker = checkerWithSelfTest { null }

        val item = checker.check().single { it.id == RECEIVE_SELF_TEST_ID }

        assertEquals(HealthCheckState.INFO, item.state)
        assertNull(item.fixLabel)
        assertNull(item.onFix)
    }

    /** 未実行状態は INFO・「テスト実行」ラベルで、onFix はフェイクの startSelfTest() を呼ぶ。 */
    @Test
    fun selfTestItemIsInfoWithStartLabelWhenNotRun() = runTest {
        val fake = FakeDesktopSelfTest(SelfTestStatus.NotRun)
        val checker = checkerWithSelfTest { fake }

        val item = checker.check().single { it.id == RECEIVE_SELF_TEST_ID }
        assertEquals(HealthCheckState.INFO, item.state)
        assertEquals("テスト実行", item.fixLabel)
        item.onFix?.invoke()
        assertEquals(1, fake.startCount)
    }

    /** 実行中は INFO で「再実行」ラベルを出す（probe 側が再入を抑止するため押下は可能）。 */
    @Test
    fun selfTestItemIsInfoWithRerunLabelWhenRunning() = runTest {
        val fake = FakeDesktopSelfTest(SelfTestStatus.Running)
        val checker = checkerWithSelfTest { fake }

        val item = checker.check().single { it.id == RECEIVE_SELF_TEST_ID }
        assertEquals(HealthCheckState.INFO, item.state)
        assertEquals("再実行", item.fixLabel)
    }

    /** 到達確認済みは PASS になる。 */
    @Test
    fun selfTestItemIsPassWhenDelivered() = runTest {
        val fake = FakeDesktopSelfTest(SelfTestStatus.Done(SelfTestResult.Delivered, atEpochMillis = 0))
        val checker = checkerWithSelfTest { fake }

        val item = checker.check().single { it.id == RECEIVE_SELF_TEST_ID }
        assertEquals(HealthCheckState.PASS, item.state)
    }

    /** タイムアウトは FAILING になり、5 秒以内に届かなかった旨の detail を持つ。 */
    @Test
    fun selfTestItemIsFailingWithDetailWhenTimeout() = runTest {
        val fake = FakeDesktopSelfTest(SelfTestStatus.Done(SelfTestResult.Timeout, atEpochMillis = 0))
        val checker = checkerWithSelfTest { fake }

        val item = checker.check().single { it.id == RECEIVE_SELF_TEST_ID }
        assertEquals(HealthCheckState.FAILING, item.state)
        assertTrue(item.detail!!.contains("5 秒以内"))
    }

    /** publish 拒否は FAILING になり、HTTP ステータスを detail に含める。 */
    @Test
    fun selfTestItemIsFailingWithStatusWhenPublishRejected() = runTest {
        val fake = FakeDesktopSelfTest(SelfTestStatus.Done(SelfTestResult.PublishRejected(403), atEpochMillis = 0))
        val checker = checkerWithSelfTest { fake }

        val item = checker.check().single { it.id == RECEIVE_SELF_TEST_ID }
        assertEquals(HealthCheckState.FAILING, item.state)
        assertTrue(item.detail!!.contains("403"))
    }

    /** publish 自体が失敗した場合も FAILING になる。 */
    @Test
    fun selfTestItemIsFailingWhenPublishFailed() = runTest {
        val fake = FakeDesktopSelfTest(SelfTestStatus.Done(SelfTestResult.PublishFailed, atEpochMillis = 0))
        val checker = checkerWithSelfTest { fake }

        val item = checker.check().single { it.id == RECEIVE_SELF_TEST_ID }
        assertEquals(HealthCheckState.FAILING, item.state)
    }

    /**
     * 項目 id は受信のセットアップ手順（[ReceiveSetupSteps.orderedIds]）と重複しないこと。
     * setupBannerTarget が未達を動作チェック（Desktop に空の誘導先を持たない）へ誘導する前提を固定化する。
     */
    @Test
    fun selfTestItemIdDoesNotCollideWithReceiveSetupStepIds() {
        assertFalse(ReceiveSetupSteps.orderedIds.contains(RECEIVE_SELF_TEST_ID))
    }
}
