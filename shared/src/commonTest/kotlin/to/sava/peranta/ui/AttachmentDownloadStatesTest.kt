package to.sava.peranta.ui

import to.sava.peranta.blob.TransferProgress
import to.sava.peranta.blob.TransferState
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 添付のダウンロード状態（§4.3）の遷移。各 `mark*` が添付カードの出し分けに使う状態を作ることを確かめる。 */
class AttachmentDownloadStatesTest {

    private fun ref(blobId: String = "blob-1", sizeBytes: Long = 2048) = AttachmentRef(
        blobId = blobId,
        url = "https://ntfy.example/file/$blobId",
        fileName = "$blobId.jpg",
        mimeType = "image/jpeg",
        sizeBytes = sizeBytes,
        kind = AttachmentKind.IMAGE,
        enc = BlobEnc(keyId = "k1", saltBase64 = "c2FsdA==", chunkSize = 1024, totalChunks = 2),
    )

    /** 一度も書き込んでいない blob は状態を持たない。 */
    @Test
    fun unknownBlobHasNoState() {
        val states = AttachmentDownloadStates()

        assertNull(states.stateOf("blob-1"))
        assertTrue(states.states.value.isEmpty())
    }

    /** 開始は 0 バイトの進行中になり、取得済みではない。 */
    @Test
    fun markRunningStartsAtZeroBytes() {
        val states = AttachmentDownloadStates()

        states.markRunning("blob-1", totalBytes = 2048)

        val state = states.stateOf("blob-1")
        assertEquals(TransferProgress(0, 2048, TransferState.RUNNING), state?.progress)
        assertEquals(false, state?.cached)
        assertNull(state?.thumbnail)
    }

    /** 進捗は転送済みバイト数を持つ進行中になる。 */
    @Test
    fun markProgressKeepsTransferredBytes() {
        val states = AttachmentDownloadStates()
        states.markRunning("blob-1", totalBytes = 2048)

        states.markProgress("blob-1", transferredBytes = 1024, totalBytes = 2048)

        assertEquals(TransferProgress(1024, 2048, TransferState.RUNNING), states.stateOf("blob-1")?.progress)
    }

    /** 取得済みは宣言サイズぶん完了した状態になり、渡したサムネイルを持つ。 */
    @Test
    fun markCachedCompletesWithDeclaredSize() {
        val states = AttachmentDownloadStates()
        states.markRunning("blob-1", totalBytes = 2048)

        states.markCached(ref(sizeBytes = 2048), thumbnail = null)

        val state = states.stateOf("blob-1")
        assertEquals(TransferProgress(2048, 2048, TransferState.COMPLETED), state?.progress)
        assertEquals(true, state?.cached)
        assertNull(state?.thumbnail)
    }

    /** 失敗は 0 バイトの失敗になり、取得済みではない（カードは再試行を出す）。 */
    @Test
    fun markFailedIsNotCached() {
        val states = AttachmentDownloadStates()
        states.markProgress("blob-1", transferredBytes = 1024, totalBytes = 2048)

        states.markFailed("blob-1", totalBytes = 2048)

        val state = states.stateOf("blob-1")
        assertEquals(TransferProgress(0, 2048, TransferState.FAILED), state?.progress)
        assertEquals(false, state?.cached)
    }

    /** キャンセルは大きさを持たないキャンセル済みになる（カードは未取得の表示へ戻る）。 */
    @Test
    fun markCancelledHasNoSize() {
        val states = AttachmentDownloadStates()
        states.markProgress("blob-1", transferredBytes = 1024, totalBytes = 2048)

        states.markCancelled("blob-1")

        val state = states.stateOf("blob-1")
        assertEquals(TransferProgress(0, 0, TransferState.CANCELLED), state?.progress)
        assertEquals(false, state?.cached)
    }

    /** 失敗のあとの再試行は、失敗の状態を残さず進行中から取得済みへ進む。 */
    @Test
    fun retryAfterFailureReplacesState() {
        val states = AttachmentDownloadStates()
        states.markFailed("blob-1", totalBytes = 2048)

        states.markRunning("blob-1", totalBytes = 2048)
        assertEquals(TransferState.RUNNING, states.stateOf("blob-1")?.progress?.state)

        states.markCached(ref(), thumbnail = null)
        assertEquals(TransferState.COMPLETED, states.stateOf("blob-1")?.progress?.state)
    }

    /** ある blob への書き込みは、ほかの blob の状態を変えない。 */
    @Test
    fun markingOneBlobLeavesOthersUntouched() {
        val states = AttachmentDownloadStates()
        states.markCached(ref("blob-a"), thumbnail = null)

        states.markFailed("blob-b", totalBytes = 10)

        assertEquals(true, states.stateOf("blob-a")?.cached)
        assertFalse(states.stateOf("blob-b")?.cached ?: true)
    }

    /** 公開する [AttachmentDownloadStates.states] は、[AttachmentDownloadStates.stateOf] と同じ状態を返す。 */
    @Test
    fun publishedStatesMatchStateOf() {
        val states = AttachmentDownloadStates()

        states.markRunning("blob-1", totalBytes = 2048)

        assertEquals(states.stateOf("blob-1"), states.states.value["blob-1"])
    }
}
