package to.sava.peranta.android

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidAttachmentActionsTest {

    /** ユーザーがピッカーをキャンセルした（Uri なし）なら何もしない。 */
    @Test
    fun cancelledPickIsIgnored() {
        assertEquals(SaveDocumentOutcome.CANCELLED, saveDocumentOutcome(hasUri = false, hasSaveTarget = true))
        assertEquals(SaveDocumentOutcome.CANCELLED, saveDocumentOutcome(hasUri = false, hasSaveTarget = false))
    }

    /** 保存先は返ったが保存対象を引けない（blobId・履歴消失）ときは、空ファイルを黙認せず失敗として扱う。 */
    @Test
    fun destinationWithoutTargetIsFailure() {
        assertEquals(SaveDocumentOutcome.MISSING_TARGET, saveDocumentOutcome(hasUri = true, hasSaveTarget = false))
    }

    /** 保存先も保存対象も揃えばコピーへ進む。 */
    @Test
    fun destinationWithTargetProceeds() {
        assertEquals(SaveDocumentOutcome.PROCEED, saveDocumentOutcome(hasUri = true, hasSaveTarget = true))
    }
}
