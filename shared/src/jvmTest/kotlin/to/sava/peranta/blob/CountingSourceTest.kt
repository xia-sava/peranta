package to.sava.peranta.blob

import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CountingSourceTest {

    /** 読み出しのたびに、その回に読めたバイト数を知らせる。終端（-1）の読み出しは知らせない。 */
    @Test
    fun reportsByteCountOfEachRead() {
        val reported = mutableListOf<Long>()
        val source = CountingSource(ByteArrayInputStream(ByteArray(5))) { reported.add(it) }
        val buffer = ByteArray(3)

        val reads = List(3) { source.read(buffer, 0, buffer.size) }

        assertEquals(listOf(3, 2, -1), reads)
        assertEquals(listOf(3L, 2L), reported)
    }

    /** 長さ 0 の読み出しは知らせない。 */
    @Test
    fun zeroLengthReadIsNotReported() {
        val reported = mutableListOf<Long>()
        val source = CountingSource(ByteArrayInputStream(ByteArray(5))) { reported.add(it) }

        assertEquals(0, source.read(ByteArray(3), 0, 0))
        assertTrue(reported.isEmpty())
    }

    /** blob 本体のチャンネルとして読み切ると、中身は元のままで、知らされた量の合計は中身の長さに一致する。 */
    @Test
    fun readingAsChannelReportsWholeLength() = runTest {
        val content = ByteArray(3 * 8192 + 17) { it.toByte() }
        val reported = mutableListOf<Long>()
        val channel = CountingSource(ByteArrayInputStream(content)) { synchronized(reported) { reported.add(it) } }
            .toByteReadChannel()

        val read = channel.readBuffer().readByteArray()

        assertContentEquals(content, read)
        assertEquals(content.size.toLong(), synchronized(reported) { reported.sum() })
    }

    /** 閉じると元のストリームも閉じる（Windows は掴んだままのファイルを消せない）。 */
    @Test
    fun closingClosesSource() {
        var closed = false
        val inner = object : ByteArrayInputStream(ByteArray(1)) {
            override fun close() {
                closed = true
                super.close()
            }
        }

        CountingSource(inner) {}.close()

        assertTrue(closed)
    }
}
