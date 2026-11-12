package to.sava.peranta.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import to.sava.peranta.platform.RecordingLogWriter
import to.sava.peranta.platform.recordingLogger
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** ダウンロード済みとして扱う配布物の中身。 */
private const val RELEASE_CONTENT = "peranta-release"

/** 配布物と一致しない sha256。 */
private val MISMATCHED_DIGEST = "0".repeat(64)

/** 書きかけができてから取得を失敗させる受け入れ量。応答の本体より小さく、書き写しの読み取り単位より大きい。 */
private const val PARTIAL_LIMIT_BYTES = 100_000L

/** 受け入れ量を超える応答の本体の長さ。 */
private const val OVERSIZE_BODY_BYTES = 300_000

/** 取得に使う URL の既定値。 */
private const val RELEASE_URL = "https://example.com/peranta.msi"

/** [downloadVerifiedRelease] へ渡す取得の型。 */
private typealias ReleaseDownload = suspend (url: String, onProgress: (received: Long, total: Long) -> Unit) -> File

/**
 * 配布物の取得と照合（[downloadVerifiedRelease]）の進行を固定する（§12）。
 * 取得に失敗した・照合に落ちた配布物を残さないこと、どこで落ちたかを失敗の理由で分けること、
 * 照合を通った配布物だけを返すこと、キャンセルを握らずに伝えることを確かめる。
 */
class ReleaseFetchTest {

    private val dir: File = Files.createTempDirectory("peranta-release-fetch-test").toFile()

    /** 配布物を置く場所。各テストの取得はここへ書く。 */
    private val target: File = File(dir, "peranta-update.msi")

    private val states = mutableListOf<UpdateInstallState>()
    private val discarded = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun available(sha256: String, url: String = RELEASE_URL) = UpdateStatus.Available("2.0.0", url, sha256)

    private suspend fun fetch(available: UpdateStatus.Available, download: ReleaseDownload): File? =
        downloadVerifiedRelease(
            available = available,
            download = download,
            discard = { file ->
                discarded += file
                file.deleteRecursively()
            },
            onState = { states += it },
            log = recordingLogger(RecordingLogWriter(), "Updater"),
        )

    /** [RELEASE_CONTENT] を [target] へ書き、受信量を 1 回知らせて返す取得。 */
    private val writingDownload: ReleaseDownload = { _, onProgress ->
        target.writeText(RELEASE_CONTENT)
        onProgress(target.length(), target.length())
        target
    }

    /** [client] の応答を本物の [downloadToFile] で [target] へ受ける取得。 */
    private fun httpDownload(client: HttpClient, maxBytes: Long = MAX_RELEASE_BYTES): ReleaseDownload =
        { url, onProgress ->
            client.downloadToFile(url, target, maxBytes = maxBytes, onProgress = onProgress)
            target
        }

    private fun digestOf(content: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(content.encodeToByteArray())
            .joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /** 照合を通った配布物はそのまま返り、破棄されず、最後の状態は照合中のまま残る。 */
    @Test
    fun matchingDigestReturnsReleaseAndStopsAtVerifying() = runTest {
        val release = fetch(available(digestOf(RELEASE_CONTENT)), writingDownload)

        assertSame(target, release)
        assertTrue(target.exists())
        assertTrue(discarded.isEmpty())
        val length = RELEASE_CONTENT.length.toLong()
        assertEquals(listOf(UpdateInstallState.Downloading(length, length), UpdateInstallState.Verifying), states)
    }

    /** sha256 が食い違う配布物は破棄され、照合中を経て照合の失敗で止まる。 */
    @Test
    fun mismatchedDigestDiscardsReleaseAndReportsVerifyFailure() = runTest {
        val release = fetch(available(MISMATCHED_DIGEST), writingDownload)

        assertNull(release)
        assertEquals(listOf(target), discarded)
        assertFalse(target.exists(), "rejected release was left on disk")
        val length = RELEASE_CONTENT.length.toLong()
        assertEquals(
            listOf(
                UpdateInstallState.Downloading(length, length),
                UpdateInstallState.Verifying,
                UpdateInstallState.Failed(UPDATE_VERIFY_FAILED_MESSAGE),
            ),
            states,
        )
    }

    /** 照合の期待値が空の配布物も照合に落ちて破棄される（照合を省く経路を作らない）。 */
    @Test
    fun blankExpectedDigestIsRejected() = runTest {
        val release = fetch(available(""), writingDownload)

        assertNull(release)
        assertFalse(target.exists(), "unverified release was left on disk")
        assertEquals(UpdateInstallState.Failed(UPDATE_VERIFY_FAILED_MESSAGE), states.last())
    }

    /** 途中で失敗した取得はダウンロードの失敗で止まり、照合へ進まず、書きかけを残さない。 */
    @Test
    fun downloadFailureReportsDownloadFailureAndLeavesNoPartialFile() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = ByteReadChannel(ByteArray(OVERSIZE_BODY_BYTES)),
                    status = HttpStatusCode.OK,
                    headers = headersOf(),
                )
            },
        )

        val release = fetch(available(MISMATCHED_DIGEST), httpDownload(client, maxBytes = PARTIAL_LIMIT_BYTES))

        assertNull(release)
        assertFalse(target.exists(), "partial download was left on disk")
        assertFalse(states.contains(UpdateInstallState.Verifying), "failed download reached verification")
        assertEquals(UpdateInstallState.Failed(UPDATE_DOWNLOAD_FAILED_MESSAGE), states.last())
    }

    /** https でない取得先は要求を 1 本も出さずに拒み、ダウンロードの失敗で止まる。 */
    @Test
    fun nonHttpsUrlIsRejectedWithoutRequest() = runTest {
        val engine = MockEngine {
            respond(content = ByteReadChannel(RELEASE_CONTENT.encodeToByteArray()), status = HttpStatusCode.OK)
        }

        val release = fetch(
            available(digestOf(RELEASE_CONTENT), url = "http://example.com/peranta.msi"),
            httpDownload(HttpClient(engine)),
        )

        assertNull(release)
        assertTrue(engine.requestHistory.isEmpty(), "a request was sent to a non-https url")
        assertFalse(target.exists())
        assertEquals(listOf<UpdateInstallState>(UpdateInstallState.Failed(UPDATE_DOWNLOAD_FAILED_MESSAGE)), states)
    }

    /** 取得の済んだ配布物を照合のために読めなければ破棄し、ダウンロードの失敗で止まる。 */
    @Test
    fun unreadableReleaseIsDiscardedAsDownloadFailure() = runTest {
        val unreadable = File(dir, "unreadable-release").apply { mkdirs() }

        val release = fetch(available(digestOf(RELEASE_CONTENT))) { _, _ -> unreadable }

        assertNull(release)
        assertEquals(listOf(unreadable), discarded)
        assertFalse(unreadable.exists(), "unverified release was left on disk")
        assertEquals(
            listOf(UpdateInstallState.Verifying, UpdateInstallState.Failed(UPDATE_DOWNLOAD_FAILED_MESSAGE)),
            states,
        )
    }

    /** 取得中のキャンセルは握られずに伝わる。呼び出しは戻らず、失敗の状態も出ず、破棄も呼ばれない。 */
    @Test
    fun cancellationPropagatesWithoutReportingFailure() = runTest {
        val started = CompletableDeferred<Unit>()
        var returned = false
        val job = launch {
            fetch(available(digestOf(RELEASE_CONTENT))) { _, _ ->
                started.complete(Unit)
                awaitCancellation()
            }
            returned = true
        }

        started.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertFalse(returned, "cancellation was swallowed")
        assertTrue(states.none { it is UpdateInstallState.Failed }, "cancellation was reported as a failure: $states")
        assertTrue(discarded.isEmpty())
    }
}
