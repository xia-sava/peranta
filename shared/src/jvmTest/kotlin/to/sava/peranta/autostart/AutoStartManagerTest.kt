package to.sava.peranta.autostart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoStartManagerTest {

    private class FakeRegistry(var command: String? = null, var registerSucceeds: Boolean = true) : AutoStartRegistry {
        val registered = mutableListOf<String>()
        var unregisterCount = 0
        override fun currentCommand(): String? = command
        override fun register(command: String): Boolean {
            if (!registerSucceeds) return false
            this.command = command
            registered += command
            return true
        }

        override fun unregister() {
            command = null
            unregisterCount++
        }
    }

    private val appPath = "C:\\Program Files\\Peranta\\Peranta.exe"

    /** 配布物パスがあれば、クォート済みで --minimized 付きの起動コマンドを組む。 */
    @Test
    fun expectedCommandQuotesPathAndAddsMinimized() {
        val manager = AutoStartManager(FakeRegistry(), appPath)
        assertEquals("\"$appPath\" --minimized", manager.expectedCommand())
        assertTrue(manager.isSupported)
    }

    /** 開発実行（パス null）では自動起動を扱わず、状態は非対応になる。 */
    @Test
    fun notSupportedWhenNoAppPath() {
        val manager = AutoStartManager(FakeRegistry(command = "anything"), appPath = null)
        assertFalse(manager.isSupported)
        assertNull(manager.expectedCommand())
        assertEquals(AutoStartStatus.NOT_SUPPORTED, manager.status())
        assertFalse(manager.isEnabled())
    }

    /** 登録済みコマンドが現在の期待コマンドと一致すれば有効と判定する。 */
    @Test
    fun enabledWhenRegisteredCommandMatches() {
        val manager = AutoStartManager(FakeRegistry(command = "\"$appPath\" --minimized"), appPath)
        assertTrue(manager.isEnabled())
        assertEquals(AutoStartStatus.ENABLED, manager.status())
    }

    /** 未登録なら無効と判定する。 */
    @Test
    fun disabledWhenNotRegistered() {
        val manager = AutoStartManager(FakeRegistry(command = null), appPath)
        assertFalse(manager.isEnabled())
        assertEquals(AutoStartStatus.DISABLED, manager.status())
    }

    /** enable で期待コマンドを登録し、disable で解除する。 */
    @Test
    fun enableAndDisableWriteRegistry() {
        val registry = FakeRegistry()
        val manager = AutoStartManager(registry, appPath)
        assertTrue(manager.enable())
        assertEquals("\"$appPath\" --minimized", registry.command)
        manager.disable()
        assertNull(registry.command)
        assertEquals(1, registry.unregisterCount)
    }

    /** enable はレジストリへの登録自体が失敗すると false を返し、呼び出し元へ失敗を伝える。 */
    @Test
    fun enableReturnsFalseWhenRegistrationFails() {
        val registry = FakeRegistry(registerSucceeds = false)
        val manager = AutoStartManager(registry, appPath)
        assertFalse(manager.enable())
        assertNull(registry.command)
    }

    /** enable は非対応環境（appPath なし）では登録を試みず false を返す。 */
    @Test
    fun enableReturnsFalseWhenNotSupported() {
        val registry = FakeRegistry()
        val manager = AutoStartManager(registry, appPath = null)
        assertFalse(manager.enable())
        assertTrue(registry.registered.isEmpty())
    }

    /** reconcile は登録済みでパスが食い違うときだけ現行パスへ書き直す。 */
    @Test
    fun reconcileRewritesStalePath() {
        val registry = FakeRegistry(command = "\"C:\\Old\\Peranta.exe\" --minimized")
        val manager = AutoStartManager(registry, appPath)
        manager.reconcile()
        assertEquals("\"$appPath\" --minimized", registry.command)
        assertEquals(1, registry.registered.size)
    }

    /** reconcile は登録が現行パスと一致していれば書き込まない（冪等）。 */
    @Test
    fun reconcileNoOpWhenAlreadyCurrent() {
        val registry = FakeRegistry(command = "\"$appPath\" --minimized")
        val manager = AutoStartManager(registry, appPath)
        manager.reconcile()
        assertTrue(registry.registered.isEmpty())
    }

    /** reconcile は未登録のときは何もしない（利用者が無効にした状態を勝手に有効化しない）。 */
    @Test
    fun reconcileDoesNotEnableWhenAbsent() {
        val registry = FakeRegistry(command = null)
        val manager = AutoStartManager(registry, appPath)
        manager.reconcile()
        assertNull(registry.command)
        assertTrue(registry.registered.isEmpty())
    }
}
