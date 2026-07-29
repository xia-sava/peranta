package to.sava.peranta.blob

import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ユーザーの同意なしにネットワーク I/O を起こしてよいかの判断（§4.3.1）。 */
class AttachmentAutoFetchTest {

    private fun ref(
        sizeBytes: Long,
        mimeType: String = "image/jpeg",
        fileName: String = "photo.jpg",
        expiresAt: Long? = null,
    ) = AttachmentRef(
        blobId = "blob-1",
        url = "https://peranta.example.com/file/abc",
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        kind = AttachmentKind.IMAGE,
        blobExpiresAtEpochMillis = expiresAt,
        enc = BlobEnc(keyId = "k1", saltBase64 = "AAAAAAAAAAAAAAAAAAAAAA==", chunkSize = 1_048_576, totalChunks = 1),
    )

    /** 上限は役割ごとに違い、送信側が符号化に課している値と同じ（§4.3.1）。 */
    @Test
    fun limitsFollowTheEncodingBudgets() {
        assertEquals(64L * 1024, autoFetchLimitBytes(AutoFetchRole.SENDER_ICON))
        assertEquals(2L * 1024 * 1024, autoFetchLimitBytes(AutoFetchRole.DISPLAY_IMAGE))
    }

    /** 上限ちょうどは取りに行き、1 バイト超えたら取りに行かない。 */
    @Test
    fun senderIconIsFetchedUpToTheLimit() {
        assertTrue(shouldAutoFetch(ref(64L * 1024), AutoFetchRole.SENDER_ICON, autoDisplayImages = false, now = 0L))
        assertFalse(shouldAutoFetch(ref(64L * 1024 + 1), AutoFetchRole.SENDER_ICON, autoDisplayImages = false, now = 0L))
    }

    /** 本文画像も同じく上限ちょうどまで。宣言サイズが上限を超えたものは自動取得しない。 */
    @Test
    fun displayImageIsFetchedUpToTheLimit() {
        assertTrue(shouldAutoFetch(ref(2L * 1024 * 1024), AutoFetchRole.DISPLAY_IMAGE, autoDisplayImages = true, now = 0L))
        assertFalse(shouldAutoFetch(ref(2L * 1024 * 1024 + 1), AutoFetchRole.DISPLAY_IMAGE, autoDisplayImages = true, now = 0L))
    }

    /** 1 GiB（blob 形式の上限）を宣言した送信者アイコンは、設定に関わらず取りに行かない。 */
    @Test
    fun oversizedSenderIconIsNeverFetched() {
        val huge = ref(MAX_BLOB_SIZE_BYTES)
        assertFalse(shouldAutoFetch(huge, AutoFetchRole.SENDER_ICON, autoDisplayImages = true, now = 0L))
        assertTrue(exceedsAutoFetchLimit(huge, AutoFetchRole.SENDER_ICON))
    }

    /** 本文画像は自動表示トグルに従う。送信者アイコンはトグルの外（手動導線が無いため、§4.3.1）。 */
    @Test
    fun autoDisplayToggleAppliesToDisplayImageOnly() {
        val small = ref(1024)
        assertFalse(shouldAutoFetch(small, AutoFetchRole.DISPLAY_IMAGE, autoDisplayImages = false, now = 0L))
        assertTrue(shouldAutoFetch(small, AutoFetchRole.SENDER_ICON, autoDisplayImages = false, now = 0L))
    }

    /** 画像でない添付は自動表示の対象にならない（手動のダウンロード導線を通す）。 */
    @Test
    fun nonImageIsNotAutoDisplayed() {
        val document = ref(1024, mimeType = "application/pdf", fileName = "invoice.pdf")
        assertFalse(shouldAutoFetch(document, AutoFetchRole.DISPLAY_IMAGE, autoDisplayImages = true, now = 0L))
    }

    /** 保持期限を過ぎた blob は取りに行かない（必ず失敗するため）。 */
    @Test
    fun expiredBlobIsNotFetched() {
        val expired = ref(1024, expiresAt = 500L)
        assertTrue(isBlobExpired(expired, now = 1000L))
        assertFalse(shouldAutoFetch(expired, AutoFetchRole.SENDER_ICON, autoDisplayImages = true, now = 1000L))
        assertFalse(isBlobExpired(expired, now = 100L))
    }

    /** 期限を持たない blob は期限切れにならない。 */
    @Test
    fun blobWithoutExpiryNeverExpires() {
        assertFalse(isBlobExpired(ref(1024), now = Long.MAX_VALUE))
    }

    /** 取得済み・開始済み（失敗・キャンセルを含む）は取りに行かない。 */
    @Test
    fun alreadyFetchedOrStartedIsNotFetchedAgain() {
        val small = ref(1024)
        assertFalse(shouldAutoFetch(small, AutoFetchRole.SENDER_ICON, autoDisplayImages = true, now = 0L, alreadyFetched = true))
        assertFalse(shouldAutoFetch(small, AutoFetchRole.SENDER_ICON, autoDisplayImages = true, now = 0L, transferStarted = true))
    }
}
