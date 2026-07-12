package to.sava.peranta.blob

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FullTextAttachmentLimitTest {

    /** 上限ちょうどのサイズは自動取得の対象（超過ではない）。 */
    @Test
    fun sizeAtLimitDoesNotExceed() {
        assertFalse(exceedsFullTextAutoFetchLimit(MAX_FULL_TEXT_ATTACHMENT_BYTES))
    }

    /** 上限を 1 バイトでも超えると自動取得の対象外。 */
    @Test
    fun sizeOverLimitExceeds() {
        assertTrue(exceedsFullTextAutoFetchLimit(MAX_FULL_TEXT_ATTACHMENT_BYTES + 1))
    }

    /** 十分小さいサイズは自動取得の対象。 */
    @Test
    fun smallSizeDoesNotExceed() {
        assertFalse(exceedsFullTextAutoFetchLimit(1024))
    }
}
