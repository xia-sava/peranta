package to.sava.peranta.android

import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TransferRegistryTest {

    /** ある転送を取り除いても他が残っていれば「空」を返さない（＝サービスを止めない）。 */
    @Test
    fun removingOneWhileAnotherActiveKeepsRegistryNonEmpty() {
        val registry = TransferRegistry()
        registry.register("a", notificationId = 10, job = Job())
        registry.register("b", notificationId = 11, job = Job())

        assertFalse(registry.remove("a"), "他の転送が残っているのに空判定になった")
        assertTrue(registry.contains("b"))
        assertTrue(registry.remove("b"), "最後の転送を取り除いたのに空にならない")
        assertTrue(registry.isEmpty())
    }

    /** キャンセルは対象転送のジョブを引ける（別 ID を巻き込まない）。 */
    @Test
    fun jobLookupTargetsRequestedTransfer() {
        val registry = TransferRegistry()
        val jobA = Job()
        val jobB = Job()
        registry.register("a", notificationId = 10, job = jobA)
        registry.register("b", notificationId = 11, job = jobB)

        assertSame(jobA, registry.jobOf("a"))
        assertSame(jobB, registry.jobOf("b"))
        assertEquals(10, registry.notificationIdOf("a"))
        assertNull(registry.jobOf("unknown"))
        assertNull(registry.notificationIdOf("unknown"))
    }

    /** 未登録 ID の除去は既存の進行中転送に影響しない。 */
    @Test
    fun removingUnknownDoesNotDisturbActiveTransfers() {
        val registry = TransferRegistry()
        registry.register("a", notificationId = 10, job = Job())

        assertFalse(registry.remove("unknown"), "未登録の除去で空判定になった")
        assertTrue(registry.contains("a"))
    }
}
