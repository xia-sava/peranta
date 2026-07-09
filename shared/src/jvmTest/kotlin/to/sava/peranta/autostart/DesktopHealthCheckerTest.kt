package to.sava.peranta.autostart

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
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
}
