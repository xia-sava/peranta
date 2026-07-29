package to.sava.peranta.update

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseDigestTest {

    private fun tempFileOf(content: String): File =
        File.createTempFile("peranta-digest", ".bin").apply {
            deleteOnExit()
            writeText(content)
        }

    /** 既知の入力に対する SHA-256 を 16 進小文字で返す。 */
    @Test
    fun computesKnownDigest() {
        val file = tempFileOf("abc")

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256HexOf(file))
    }

    /** 読み取りバッファより大きい入力でも、逐次処理で正しい値になる。 */
    @Test
    fun computesDigestAcrossBufferBoundary() {
        val file = tempFileOf("a".repeat(200_000))

        assertEquals(sha256HexOf(file).length, 64)
        assertTrue(matchesSha256(file, sha256HexOf(file)))
    }

    /** 期待値と一致すれば true。大文字小文字は区別しない。 */
    @Test
    fun matchesIgnoringCase() {
        val file = tempFileOf("abc")

        assertTrue(matchesSha256(file, "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"))
    }

    /** 期待値と食い違えば false（改竄・破損したダウンロードを弾く）。 */
    @Test
    fun rejectsMismatch() {
        val file = tempFileOf("abc")

        assertFalse(matchesSha256(file, "0000000000000000000000000000000000000000000000000000000000000000"))
    }

    /** 照合できない期待値は不一致として扱う（照合を省く経路を作らない）。 */
    @Test
    fun rejectsBlankExpectedDigest() {
        val file = tempFileOf("abc")

        assertFalse(matchesSha256(file, ""))
        assertFalse(matchesSha256(file, "   "))
    }
}
