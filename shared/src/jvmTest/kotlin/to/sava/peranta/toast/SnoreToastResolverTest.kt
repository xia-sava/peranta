package to.sava.peranta.toast

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnoreToastResolverTest {

    /** 同一内容なら一致とみなし、コピーをスキップできる。 */
    @Test
    fun matchesIdenticalContent() {
        val bytes = ByteArray(1024) { it.toByte() }
        assertTrue(snoreToastExeMatches(bytes.copyOf(), bytes))
    }

    /** サイズが同じでも内容が違えば一致しない（sha256 で弾く）。 */
    @Test
    fun rejectsSameSizeDifferentContent() {
        val existing = ByteArray(1024) { it.toByte() }
        val bundled = existing.copyOf().also { it[512] = (it[512] + 1).toByte() }
        assertFalse(snoreToastExeMatches(existing, bundled))
    }

    /** サイズが違えば一致しない。 */
    @Test
    fun rejectsDifferentSize() {
        assertFalse(snoreToastExeMatches(ByteArray(1024), ByteArray(2048)))
    }

    /** sha256 は同一内容で同じ、異なる内容で異なる。 */
    @Test
    fun sha256DistinguishesContent() {
        val bytes = ByteArray(64) { it.toByte() }
        assertTrue(sha256Hex(bytes) == sha256Hex(bytes.copyOf()))
        assertFalse(sha256Hex(bytes) == sha256Hex(ByteArray(64) { (it + 1).toByte() }))
    }
}
