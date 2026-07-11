package to.sava.peranta.blob

import kotlin.test.Test
import kotlin.test.assertEquals

class TransferProgressTest {

    /** 進捗率は転送済み/総サイズを 0..100 の整数へ丸める。 */
    @Test
    fun percentIsRatioClamped() {
        assertEquals(0, TransferProgress(0, 100, TransferState.RUNNING).percent)
        assertEquals(50, TransferProgress(50, 100, TransferState.RUNNING).percent)
        assertEquals(100, TransferProgress(100, 100, TransferState.COMPLETED).percent)
    }

    /** 総サイズ未確定（0 以下）のときは 0% とする。 */
    @Test
    fun percentIsZeroWhenTotalUnknown() {
        assertEquals(0, TransferProgress(10, 0, TransferState.RUNNING).percent)
    }

    /** 転送済みが総サイズを超えても 100% で頭打ちする。 */
    @Test
    fun percentClampsAboveTotal() {
        assertEquals(100, TransferProgress(150, 100, TransferState.RUNNING).percent)
    }

    /** 開始直後のヘルパは 0 バイト・RUNNING を返す。 */
    @Test
    fun runningStartsAtZero() {
        val progress = TransferProgress.running(2048)
        assertEquals(0, progress.transferredBytes.toInt())
        assertEquals(TransferState.RUNNING, progress.state)
        assertEquals(2048, progress.totalBytes.toInt())
    }
}
