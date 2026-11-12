package to.sava.peranta

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import to.sava.peranta.ui.ATTACHMENT_NO_VIEWER_MESSAGE
import to.sava.peranta.ui.ATTACHMENT_SAVE_FAILED_MESSAGE
import to.sava.peranta.ui.REFUSED_OPEN_MESSAGE
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Desktop の添付の「開く」「保存」（§4.3）と、その失敗のタイムラインへの記録（§10.1）。
 * OS への受け渡し（既定アプリで開く・保存ダイアログ）はフェイクに差し替え、実際のアプリやダイアログは開かない。
 */
class DesktopAttachmentActionsTest {

    private val baseDir: File = Files.createTempDirectory("peranta-attach-actions-test").toFile()
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.Default)

    private val errors = ConcurrentLinkedQueue<String>()
    private val opened = ConcurrentLinkedQueue<File>()
    private val suggestedNames = ConcurrentLinkedQueue<String>()

    @AfterTest
    fun cleanup() {
        scopeJob.cancel()
        baseDir.deleteRecursively()
    }

    private fun ref(blobId: String = "blob-1", fileName: String = "photo.jpg", mimeType: String = "image/jpeg") =
        AttachmentRef(
            blobId = blobId,
            url = "https://ntfy.example/file/$blobId",
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = 3,
            kind = AttachmentKind.IMAGE,
            enc = BlobEnc(keyId = "k1", saltBase64 = "c2FsdA==", chunkSize = 1024, totalChunks = 1),
        )

    /** [ref] の復号済みファイルを作る。 */
    private fun cachedFile(ref: AttachmentRef): File =
        File(baseDir, "cache/${ref.blobId}/${ref.fileName}").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }

    private fun actions(
        refs: List<AttachmentRef>,
        files: Map<String, File>,
        openFile: (File) -> Unit = { opened.add(it) },
        chooseSaveDestination: (String) -> File? = { error("save dialog must not be shown") },
    ) = DesktopAttachmentActions(
        scope = scope,
        refFor = { blobId -> refs.firstOrNull { it.blobId == blobId } },
        cachedFileFor = { ref -> files[ref.blobId] },
        openFile = openFile,
        chooseSaveDestination = { name ->
            suggestedNames.add(name)
            chooseSaveDestination(name)
        },
        reportError = { errors.add(it) },
    )

    /** [scope] に出した処理（OS への受け渡し・コピー・記録）がすべて終わるまで待つ。 */
    private fun awaitLaunched() = runBlocking { scopeJob.children.toList().joinAll() }

    /** 復号済みファイルを OS へ渡して開き、何も記録しない。 */
    @Test
    fun opensCachedFile() {
        val photo = ref()
        val file = cachedFile(photo)

        actions(listOf(photo), mapOf(photo.blobId to file)).open(photo.blobId)
        awaitLaunched()

        assertEquals(listOf(file), opened.toList())
        assertTrue(errors.isEmpty())
    }

    /** 開く導線を出さない種別は OS へ渡さず、保存を促す文言を記録する（§4.3.2）。 */
    @Test
    fun refusedTypeIsRecordedWithoutOpening() {
        val disguised = ref(fileName = "invoice.pdf.exe", mimeType = "image/png")
        val file = cachedFile(disguised)

        actions(listOf(disguised), mapOf(disguised.blobId to file)).open(disguised.blobId)
        awaitLaunched()

        assertTrue(opened.isEmpty())
        assertEquals(listOf(REFUSED_OPEN_MESSAGE), errors.toList())
    }

    /** OS へ渡せない環境・渡して失敗したときは、開けるアプリが無い旨を記録する。 */
    @Test
    fun openFailureIsRecordedAsNoViewer() {
        val photo = ref()
        val file = cachedFile(photo)
        val failures = listOf(UnsupportedOperationException("not supported"), IOException("no association"))

        failures.forEach { failure ->
            actions(listOf(photo), mapOf(photo.blobId to file), openFile = { throw failure }).open(photo.blobId)
        }
        awaitLaunched()

        assertEquals(listOf(ATTACHMENT_NO_VIEWER_MESSAGE, ATTACHMENT_NO_VIEWER_MESSAGE), errors.toList())
    }

    /** 保存先を選ぶと復号済みファイルをそこへコピーする。保存ダイアログの初期のファイル名は元のファイル名にする。 */
    @Test
    fun savesToChosenDestination() {
        val photo = ref()
        val file = cachedFile(photo)
        val destination = File(baseDir, "saved.jpg")

        actions(listOf(photo), mapOf(photo.blobId to file), chooseSaveDestination = { destination }).save(photo.blobId)
        awaitLaunched()

        assertContentEquals(file.readBytes(), destination.readBytes())
        assertEquals(listOf(photo.fileName), suggestedNames.toList())
        assertTrue(errors.isEmpty())
    }

    /** 保存ダイアログを出し、保存先を選ばずに閉じられたときは、何もコピーせず記録もしない。 */
    @Test
    fun closingSaveDialogRecordsNothing() {
        val photo = ref()
        val file = cachedFile(photo)

        actions(listOf(photo), mapOf(photo.blobId to file), chooseSaveDestination = { null }).save(photo.blobId)
        awaitLaunched()

        assertEquals(listOf(photo.fileName), suggestedNames.toList())
        assertTrue(errors.isEmpty())
    }

    /** 選んだ保存先へコピーできなかったときは、保存の失敗を記録する。 */
    @Test
    fun copyFailureIsRecorded() {
        val photo = ref()
        val file = cachedFile(photo)
        val unreachable = File(baseDir, "missing-dir/saved.jpg")

        actions(listOf(photo), mapOf(photo.blobId to file), chooseSaveDestination = { unreachable }).save(photo.blobId)
        awaitLaunched()

        assertEquals(listOf(ATTACHMENT_SAVE_FAILED_MESSAGE), errors.toList())
    }

    /** 保存ダイアログを出せなかったときも、保存の失敗を記録する。 */
    @Test
    fun saveDialogFailureIsRecorded() {
        val photo = ref()
        val file = cachedFile(photo)

        actions(
            listOf(photo),
            mapOf(photo.blobId to file),
            chooseSaveDestination = { throw IllegalStateException("no display") },
        ).save(photo.blobId)
        awaitLaunched()

        assertEquals(listOf(ATTACHMENT_SAVE_FAILED_MESSAGE), errors.toList())
    }

    /**
     * 参照が引けない（履歴から消えた）・復号済みファイルが無い（剪定で消えた）ときは、ログに残すだけで記録しない。
     * OS へも渡さず、保存ダイアログも出さない。
     */
    @Test
    fun missingRefOrFileIsOnlyLogged() {
        val photo = ref()
        val withoutFile = actions(listOf(photo), files = emptyMap())
        val withoutRef = actions(refs = emptyList(), files = emptyMap())

        withoutFile.open(photo.blobId)
        withoutFile.save(photo.blobId)
        withoutRef.open(photo.blobId)
        withoutRef.save(photo.blobId)
        awaitLaunched()

        assertTrue(opened.isEmpty())
        assertTrue(suggestedNames.isEmpty())
        assertTrue(errors.isEmpty())
    }
}
