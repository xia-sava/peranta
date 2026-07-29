package to.sava.peranta.timeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 抑止の単位が [ErrorKind.origin] で決まること（§10.5）。 */
class ErrorSuppressorTest {

    private val window = ERROR_SUPPRESSION_WINDOW_MILLIS

    /** 外部入力起因は文言が違っても種別ごとに 1 件へ抑える（文言を変えられても積み上がらない）。 */
    @Test
    fun untrustedInputIsSuppressedPerKindRegardlessOfMessage() {
        val suppressor = ErrorSuppressor()

        assertTrue(suppressor.allows(ErrorKind.DECRYPTION, "一度目", 0))
        assertFalse(suppressor.allows(ErrorKind.DECRYPTION, "二度目", 1))
        assertFalse(suppressor.allows(ErrorKind.DECRYPTION, "三度目", 2))
    }

    /** 自端末起因は文言ごとに見るため、別々の失敗はそれぞれ見える。 */
    @Test
    fun localOperationIsSuppressedPerMessage() {
        val suppressor = ErrorSuppressor()

        assertTrue(suppressor.allows(ErrorKind.COMMAND_EXECUTION, "返信本文がありません", 0))
        assertTrue(suppressor.allows(ErrorKind.COMMAND_EXECUTION, "アクション番号がありません", 1))
        assertFalse(suppressor.allows(ErrorKind.COMMAND_EXECUTION, "返信本文がありません", 2))
    }

    /** 窓を跨げば再び通る。 */
    @Test
    fun allowsAgainAfterWindow() {
        val suppressor = ErrorSuppressor()

        assertTrue(suppressor.allows(ErrorKind.ENVELOPE_DECODE, "壊れた本文", 0))
        assertFalse(suppressor.allows(ErrorKind.ENVELOPE_DECODE, "壊れた本文", window))
        assertTrue(suppressor.allows(ErrorKind.ENVELOPE_DECODE, "壊れた本文", window + 1))
    }

    /** 抑止した件数は次に通す 1 件へ引き継がれ、取り出すと 0 に戻る。 */
    @Test
    fun suppressedCountIsCarriedToTheNextAllowedReport() {
        val suppressor = ErrorSuppressor()
        suppressor.allows(ErrorKind.DECRYPTION, "復号失敗", 0)
        repeat(4) { suppressor.allows(ErrorKind.DECRYPTION, "復号失敗", it + 1L) }

        assertTrue(suppressor.allows(ErrorKind.DECRYPTION, "復号失敗", window + 1))

        assertEquals(4, suppressor.takeSuppressedCount(ErrorKind.DECRYPTION, "復号失敗"))
        assertEquals(0, suppressor.takeSuppressedCount(ErrorKind.DECRYPTION, "復号失敗"))
    }
}
