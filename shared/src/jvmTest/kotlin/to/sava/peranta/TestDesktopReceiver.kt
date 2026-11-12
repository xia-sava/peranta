package to.sava.peranta

import co.touchlab.kermit.Logger
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineDispatcher
import to.sava.peranta.blob.BlobTransport
import to.sava.peranta.blob.FakeBlobTransport
import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.platform.ioDispatcher
import to.sava.peranta.send.FakeTimelineStore
import to.sava.peranta.timeline.TimelineFeed
import to.sava.peranta.toast.NoOpToaster
import to.sava.peranta.toast.Toaster
import java.io.File

/**
 * テスト用の [DesktopReceiver] を組む。タイムラインはメモリの [feed]、復号済み添付の置き場は [attachmentsDir]、
 * blob の転送は [transport]、OS への受け渡しは [openFile] と [chooseSaveDestination] に差し替える。
 * アプリデータの領域にも、実際のアプリや保存ダイアログにも触れない。
 * 既定の [openFile] と [chooseSaveDestination] は、呼ばれると例外を投げる。
 */
internal fun testDesktopReceiver(
    attachmentsDir: File,
    config: PerantaConfig,
    repository: ConfigRepository = ConfigRepository(MapSettings()),
    toaster: Toaster = NoOpToaster,
    onToastClicked: (itemId: String) -> Unit = {},
    onToastReplyRequested: (itemId: String, actionIndex: Int) -> Unit = { _, _ -> },
    feed: TimelineFeed = TimelineFeed(FakeTimelineStore()),
    transport: BlobTransport = FakeBlobTransport(),
    dispatcher: CoroutineDispatcher = ioDispatcher,
    openFile: (File) -> Unit = { error("tests must not open files with the OS") },
    chooseSaveDestination: (String) -> File? = { error("tests must not show the save dialog") },
): DesktopReceiver = DesktopReceiver(
    config = config,
    repository = repository,
    toaster = toaster,
    onToastClicked = onToastClicked,
    onToastReplyRequested = onToastReplyRequested,
    log = Logger.withTag("TestDesktopReceiver"),
    feed = feed,
    newBlobTransport = { transport },
    attachmentsDir = attachmentsDir,
    dispatcher = dispatcher,
    openFile = openFile,
    chooseSaveDestination = chooseSaveDestination,
)
