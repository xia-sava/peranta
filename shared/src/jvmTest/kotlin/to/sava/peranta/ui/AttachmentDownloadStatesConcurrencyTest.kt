package to.sava.peranta.ui

import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 複数スレッドからの [AttachmentDownloadStates] への書き込み（§4.3）。書き手は転送・取得済みの反映・
 * 通知の画像の取得と複数のスレッドにまたがるため、別々の blob への書き込みが互いを消さないことを確かめる。
 */
class AttachmentDownloadStatesConcurrencyTest {

    private fun ref(blobId: String) = AttachmentRef(
        blobId = blobId,
        url = "https://ntfy.example/file/$blobId",
        fileName = "$blobId.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 2048,
        kind = AttachmentKind.IMAGE,
        enc = BlobEnc(keyId = "k1", saltBase64 = "c2FsdA==", chunkSize = 1024, totalChunks = 2),
    )

    /** [index] に応じて `mark*` のいずれかで [blobId] を書く。 */
    private fun write(states: AttachmentDownloadStates, blobId: String, index: Int) {
        when (index % MARK_KINDS) {
            0 -> states.markRunning(blobId, totalBytes = 2048)
            1 -> states.markProgress(blobId, transferredBytes = 1024, totalBytes = 2048)
            2 -> states.markCached(ref(blobId), thumbnail = null)
            3 -> states.markFailed(blobId, totalBytes = 2048)
            else -> states.markCancelled(blobId)
        }
    }

    /** 複数スレッドが同時に別々の blobId を書いても、書いた blob はすべて残る。 */
    @Test
    fun concurrentWritesToDifferentBlobsAreAllKept() {
        val states = AttachmentDownloadStates()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(THREADS)
        try {
            val writers = (0 until THREADS).map { thread ->
                executor.submit(
                    Callable {
                        start.await()
                        repeat(BLOBS_PER_THREAD) { index -> write(states, "blob-$thread-$index", index) }
                    },
                )
            }
            start.countDown()
            writers.forEach { it.get(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(THREADS * BLOBS_PER_THREAD, states.states.value.size)
    }

    private companion object {
        const val THREADS: Int = 8
        const val BLOBS_PER_THREAD: Int = 500
        const val MARK_KINDS: Int = 5
        const val WRITE_TIMEOUT_SECONDS: Long = 30
    }
}
