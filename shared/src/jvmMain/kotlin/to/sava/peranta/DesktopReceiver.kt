package to.sava.peranta

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import to.sava.peranta.blob.DesktopAttachmentCache
import to.sava.peranta.blob.KtorBlobTransport
import to.sava.peranta.blob.TransferProgress
import to.sava.peranta.blob.TransferState
import to.sava.peranta.blob.exceedsFullTextAutoFetchLimit
import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.config.isDevMode
import to.sava.peranta.config.withDevOverrides
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.net.KtorNtfyClient
import to.sava.peranta.net.createNtfyHttpClient
import to.sava.peranta.net.httpBaseUrl
import to.sava.peranta.pairing.SettingsController
import to.sava.peranta.platform.ioDispatcher
import to.sava.peranta.receive.LocalDismissCommandExecutor
import to.sava.peranta.receive.ReceivePipeline
import to.sava.peranta.send.CommandSender
import to.sava.peranta.send.SendPipeline
import to.sava.peranta.roster.CAPABILITY_COMMAND
import to.sava.peranta.roster.CAPABILITY_DISPLAY
import to.sava.peranta.roster.buildPresencePayload
import to.sava.peranta.roster.publishPresence
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.JsonlTimelineStore
import to.sava.peranta.timeline.ReceivedFile
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineItem
import to.sava.peranta.timeline.defaultTimelineFile
import to.sava.peranta.toast.ToastResult
import to.sava.peranta.toast.Toaster
import to.sava.peranta.toast.createDesktopToaster
import to.sava.peranta.toast.toastContentFor
import to.sava.peranta.ui.AppFilterController
import to.sava.peranta.ui.AttachmentDownloadState
import to.sava.peranta.ui.AttachmentUi
import to.sava.peranta.ui.FullTextUi
import to.sava.peranta.ui.TimelineActions
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64

/**
 * [repository] の永続化状態から Desktop 実行に必要な項目を補って設定を組み立てる。
 * [devMode] が真のときだけ開発用オーバーライド（[withDevOverrides]）を適用し、
 * 偽のとき（配布物）はオーバーライドを一切適用せず TLS を常に有効化する（§16）。
 * 端末名があれば安定 deviceId を確定し、受信 topic 未設定なら topic を採番・永続化する。
 */
private fun enrichConfig(repository: ConfigRepository, devMode: Boolean): PerantaConfig {
    val config = if (devMode) repository.load().withDevOverrides() else repository.load().copy(useTls = true)
    val deviceName = config.deviceName ?: return config
    val deviceId = repository.ensureDeviceId()
    val topic = config.receiveTopic ?: repository.ensureReceiveTopic(deviceName)
    return config.copy(deviceId = deviceId, receiveTopic = topic)
}

/**
 * Desktop 用の [ConfigRepository] を生成する。
 * 配布物（devMode 偽）では TLS を常に有効へ強制し、devMode では保存値（既定は無効）を尊重する（§16）。
 */
private fun desktopConfigRepository(settings: Settings, devMode: Boolean): ConfigRepository =
    ConfigRepository(settings, forceTls = !devMode)

/**
 * Desktop の設定を settings から読む。[enrichConfig] に委譲する薄いエントリポイント。
 */
fun loadDesktopConfig(
    settings: Settings = Settings(),
    devMode: Boolean = isDevMode(),
): PerantaConfig = enrichConfig(desktopConfigRepository(settings, devMode), devMode)

/**
 * Desktop 起動時の設定読み込みと、設定画面コントローラを同一の settings 実体から作る。
 * これにより desktopApp 側は multiplatform-settings の型に依存せず設定 UI を配線できる。
 */
class DesktopSettings(
    settings: Settings = Settings(),
    val devMode: Boolean = isDevMode(),
) {
    /** 設定ストアに紐づくリポジトリ。設定画面・アプリフィルタ画面の永続化で共有する。 */
    val repository: ConfigRepository = desktopConfigRepository(settings, devMode)

    /** 起動時に一度だけ読み込んだ設定。初期表示の判定に使う。 */
    val config: PerantaConfig = enrichConfig(repository, devMode)
    val controller: SettingsController = SettingsController(repository)

    /** 現在の永続化状態から enrichment 済みの設定を読み直す（設定変更の自動反映に使う）。 */
    fun reloadConfig(): PerantaConfig = enrichConfig(repository, devMode)
}

/**
 * Desktop 受信の中核を組み立てる。設定が揃っている（[PerantaConfig.isReadyForReceive]）
 * 前提で生成すること。受信通知は Windows トーストにも表示する（[toaster]）。
 * [repository] はアプリフィルタ画面（§10.4-1）のローカルミラー永続化と共有する設定リポジトリ。
 */
class DesktopReceiver(
    val config: PerantaConfig,
    private val repository: ConfigRepository,
    private val toaster: Toaster = createDesktopToaster(),
    private val onToastClicked: () -> Unit = {},
    private val log: Logger = Logger.withTag("DesktopReceiver"),
) {
    private val httpClient = createNtfyHttpClient()
    private val store = JsonlTimelineStore(defaultTimelineFile())
    private val cipher = MessageCipher(Base64.decode(config.sharedKeyBase64!!), config.keyId!!)
    private val ntfy = KtorNtfyClient(config, httpClient, Logger.withTag("NtfyClient"))
    private val toastJob = SupervisorJob()
    private val toastScope = CoroutineScope(toastJob + ioDispatcher)
    private val commandSender = CommandSender(config, cipher, ntfy, SendPipeline(cipher, ntfy, store))

    private val attachmentCache = DesktopAttachmentCache(
        transport = KtorBlobTransport(config, httpClient),
        sharedKey = Base64.decode(config.sharedKeyBase64!!),
        keyId = config.keyId!!,
    )
    private val attachmentStates = MutableStateFlow<Map<String, AttachmentDownloadState>>(emptyMap())

    // Compose UI スレッドと IO ディスパッチャの双方から並行アクセスされるためスレッドセーフにする。
    private val downloadJobs = ConcurrentHashMap<String, Job>()
    private val knownRefs = ConcurrentHashMap<String, AttachmentRef>()

    // 復号済みの全文添付本文を blobId 毎に保持し、再表示（スクロール復帰）での再取得を避ける（§4.3）。
    private val fullTextCache = ConcurrentHashMap<String, String>()

    // 受信専用端末では dismiss のみ意味を持つ executor を注入する。他 command 種別は no-op（§3.4）。
    private val dismissExecutor = LocalDismissCommandExecutor(
        items = ::currentItems,
        dismissLocal = { payloadId -> toaster.close(payloadId) },
    )
    private val pipeline = ReceivePipeline(
        ntfy = ntfy,
        cipher = cipher,
        store = store,
        deviceId = config.deviceId!!,
        commandExecutor = dismissExecutor,
        persistSensitiveHistory = config.persistSensitiveHistory,
        onItemAppended = ::handleAppended,
    )

    /** UI が購読するタイムライン。 */
    val items: StateFlow<List<TimelineItem>> = pipeline.items

    private fun currentItems(): List<TimelineItem> = pipeline.items.value

    /** 起動時剪定と presence 告知を行い、受信 topic の購読を開始する。キャンセルまで戻らない。 */
    suspend fun run() {
        store.prune(now = nowEpochMillis())
        runCatching { attachmentCache.prune() }
            .onFailure { log.w(it) { "attachment cache prune failed" } }
        announcePresence()
        toastScope.launch { primeCachedAttachmentStates() }
        log.i { "starting desktop receiver for device=${config.deviceName}" }
        pipeline.start(config.receiveTopic!!)
    }

    /**
     * タイムラインに現れた受信ファイルのうち、既にキャッシュ済みの添付を「取得済み」状態に反映する（§4.3）。
     * 履歴読み込み・新規受信の双方でカードを開く/保存できる状態にするため、items を購読して随時プライムする。
     */
    private suspend fun primeCachedAttachmentStates() {
        pipeline.items.collect { items ->
            items.filterIsInstance<ReceivedFile>().forEach { file ->
                file.payload.attachments.forEach { ref ->
                    knownRefs[ref.blobId] = ref
                    if (attachmentStates.value[ref.blobId] == null) {
                        attachmentCache.cachedFile(ref)?.let { markCached(ref) }
                    }
                }
            }
        }
    }

    /**
     * control topic へ自端末の presence を告知する（§3.5）。
     * control topic 未設定なら何もしない。失敗しても受信開始は妨げない。
     */
    private suspend fun announcePresence() {
        val controlTopic = config.controlTopic ?: return
        val deviceId = config.deviceId ?: return
        val receiveTopic = config.receiveTopic ?: return
        try {
            val presence = buildPresencePayload(
                deviceId = deviceId,
                deviceName = config.deviceName ?: deviceId,
                endpoint = "${config.httpBaseUrl()}/$receiveTopic",
                capabilities = listOf(CAPABILITY_DISPLAY, CAPABILITY_COMMAND),
                sender = config.sendEnabled,
                now = nowEpochMillis(),
            )
            publishPresence(cipher, ntfy, controlTopic, presence)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log.w(error) { "presence announce failed" }
        }
    }

    /** タイムラインに載った新規アイテムをトースト表示へ回す（受信処理はブロックしない）。 */
    private fun handleAppended(item: TimelineItem) {
        when (item) {
            is ReceivedNotification -> toastScope.launch { showNotificationToast(item) }
            is ErrorItem -> toastScope.launch { showErrorToast(item) }
            is ReceivedFile -> toastScope.launch { toaster.show(toastContentFor(item)) }
            else -> Unit
        }
    }

    private suspend fun showNotificationToast(item: ReceivedNotification) {
        val content = toastContentFor(item) ?: return
        when (toaster.show(content)) {
            ToastResult.ButtonDismiss -> requestDismiss(item.payload)
            ToastResult.Clicked -> {
                log.i { "toast clicked id=${item.id}" }
                onToastClicked()
            }

            else -> Unit
        }
    }

    private suspend fun showErrorToast(item: ErrorItem) {
        toaster.show(toastContentFor(item))
    }

    /**
     * トーストの「消す」押下時に既読同期（§3.4）の dismiss を全端末へブロードキャストする。
     * SMS など notificationKey を持たない通知は取り下げ対象にならないためログのみとする。
     */
    private fun requestDismiss(payload: Payload) {
        val notificationKey = (payload as? NotificationPayload)?.notificationKey ?: run {
            log.i { "dismiss ignored (no notification key) payload=${payload.id}" }
            return
        }
        toastScope.launch { commandSender.dismiss(notificationKey) }
    }

    /**
     * タイムライン UI 用の操作束を作る（§10.1）。アクション発火・非表示は送信元へ一点指定、
     * 「消す」は既読同期のため全端末へブロードキャストしつつ、表示済みトーストも取り下げる。
     * mute はアプリフィルタ画面（§10.4-1）と同じ経路（[appFilterController]）でローカルミラーへも反映する。
     */
    fun timelineActions(): TimelineActions = TimelineActions(
        invokeAction = { payload, index ->
            toastScope.launch {
                commandSender.invokeAction(payload.from, payload.notificationKey, index)
            }
        },
        dismiss = { item -> dismissFromTimeline(item) },
        muteApp = { payload ->
            appFilterController().setMirroredMute(payload.packageName, payload.from, mute = true)
        },
    )

    /**
     * 受信専用端末のアプリフィルタ画面（§10.4-1）向けコントローラを組む。
     * チェック操作は [repository] のローカルミラー（filterRules）へ反映すると同時に、送信元スマホへ
     * mute/unmute コマンドを送る。宛先はタイムライン履歴に記録された送信元 deviceId を使う。
     */
    fun appFilterController(): AppFilterController = AppFilterController(
        repository = repository,
        commandScope = toastScope,
        sendMuteCommand = { packageName, senderDeviceId, mute ->
            try {
                if (mute) {
                    commandSender.muteApp(senderDeviceId, packageName)
                } else {
                    commandSender.unmuteApp(senderDeviceId, packageName)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                log.w(error) { "failed to send mute command for $packageName" }
            }
        },
    )

    private fun dismissFromTimeline(item: ReceivedNotification) {
        toastScope.launch {
            toaster.close(item.payload.id)
            val notificationKey = (item.payload as? NotificationPayload)?.notificationKey ?: run {
                log.i { "dismiss ignored (no notification key) payload=${item.payload.id}" }
                return@launch
            }
            commandSender.dismiss(notificationKey)
        }
    }

    /**
     * タイムラインの添付カード用の操作束を作る（§4.3）。手動ダウンロード・キャンセル・開く・保存を配線し、
     * 進捗は [attachmentStates] を通じて画面へ公開する。
     */
    fun attachmentUi(): AttachmentUi = AttachmentUi(
        states = attachmentStates.asStateFlow(),
        onDownload = { ref -> startDownload(ref) },
        onCancel = { blobId -> cancelDownload(blobId) },
        onOpen = { blobId -> openAttachment(blobId) },
        onSave = { blobId -> saveAttachment(blobId) },
    )

    /**
     * タイムラインの全文添付（kind=TEXT）の自動取得口を作る（§4.3）。
     * カードのような手動導線は持たず、表示時にキャッシュ経由で復号し全文文字列を返す。取得失敗は null。
     */
    fun fullTextUi(): FullTextUi = FullTextUi(fetchFullText = ::fetchFullText)

    /**
     * 全文添付の本文を取得する。既に復号済みならメモから、無ければキャッシュへダウンロード・復号して読む（§4.3）。
     * ブロッキング I/O を含むため IO ディスパッチャで動かす。取得失敗（オフライン・期限切れ等）は握って null を返し、
     * 呼び出し側（[to.sava.peranta.ui.ExpandableText]）は切り詰めプレビューのまま据え置く。
     */
    private suspend fun fetchFullText(ref: AttachmentRef): String? {
        fullTextCache[ref.blobId]?.let { return it }
        if (exceedsFullTextAutoFetchLimit(ref.sizeBytes)) {
            log.w { "full text attachment exceeds auto fetch limit; skipping blobId=${ref.blobId} sizeBytes=${ref.sizeBytes}" }
            return null
        }
        return try {
            withContext(ioDispatcher) {
                attachmentCache.download(ref).readText(Charsets.UTF_8)
            }.also { fullTextCache[ref.blobId] = it }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log.w(error) { "full text fetch failed blobId=${ref.blobId}" }
            null
        }
    }

    /** [ref] の添付を手動ダウンロードする。既に進行中なら二重起動しない。 */
    private fun startDownload(ref: AttachmentRef) {
        knownRefs[ref.blobId] = ref
        if (downloadJobs[ref.blobId]?.isActive == true) return
        attachmentStates.update { it + (ref.blobId to AttachmentDownloadState(progress = TransferProgress.running(ref.sizeBytes))) }
        val job = toastScope.launch {
            try {
                attachmentCache.download(ref) { transferred ->
                    attachmentStates.update {
                        it + (ref.blobId to AttachmentDownloadState(
                            progress = TransferProgress(transferred, ref.sizeBytes, TransferState.RUNNING),
                        ))
                    }
                }
                markCached(ref)
                log.i { "attachment downloaded blobId=${ref.blobId}" }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                log.w(error) { "attachment download failed blobId=${ref.blobId}" }
                attachmentStates.update {
                    it + (ref.blobId to AttachmentDownloadState(
                        progress = TransferProgress(0, ref.sizeBytes, TransferState.FAILED),
                    ))
                }
            } finally {
                downloadJobs.remove(ref.blobId)
            }
        }
        downloadJobs[ref.blobId] = job
    }

    /** 進行中のダウンロードをキャンセルし、未取得状態へ戻す。 */
    private fun cancelDownload(blobId: String) {
        downloadJobs.remove(blobId)?.cancel()
        attachmentStates.update {
            it + (blobId to AttachmentDownloadState(
                progress = TransferProgress(0, 0, TransferState.CANCELLED),
            ))
        }
    }

    /** ダウンロード済みの添付を「取得済み」状態にし、画像ならサムネイルを付ける。 */
    private fun markCached(ref: AttachmentRef) {
        val file = attachmentCache.cachedFile(ref) ?: return
        val thumbnail = decodeThumbnail(ref, file)
        attachmentStates.update {
            it + (ref.blobId to AttachmentDownloadState(
                progress = TransferProgress(ref.sizeBytes, ref.sizeBytes, TransferState.COMPLETED),
                cached = true,
                thumbnail = thumbnail,
            ))
        }
    }

    /** 画像添付を復号済みファイルからデコードしてサムネイルにする。失敗時は null（種別アイコンにフォールバック）。 */
    private fun decodeThumbnail(ref: AttachmentRef, file: File): ImageBitmap? {
        if (ref.kind != AttachmentKind.IMAGE) return null
        if (file.length() > MAX_THUMBNAIL_DECODE_BYTES) return null
        return try {
            SkiaImage.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
        } catch (error: Exception) {
            log.w(error) { "thumbnail decode failed blobId=${ref.blobId}" }
            null
        }
    }

    /** 復号済みファイルを OS 既定アプリで開く（§4.3）。 */
    private fun openAttachment(blobId: String) {
        val file = cachedFileFor(blobId) ?: return
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            log.w { "Desktop open not supported" }
            return
        }
        toastScope.launch {
            try {
                Desktop.getDesktop().open(file)
            } catch (error: Exception) {
                log.w(error) { "failed to open attachment blobId=$blobId" }
            }
        }
    }

    /** 復号済みファイルを保存ダイアログ経由で任意の場所へコピーする（§4.3）。 */
    private fun saveAttachment(blobId: String) {
        val source = cachedFileFor(blobId) ?: return
        toastScope.launch {
            val dialog = FileDialog(null as Frame?, "保存", FileDialog.SAVE).apply {
                file = source.name
                isVisible = true
            }
            val directory = dialog.directory ?: return@launch
            val chosen = dialog.file ?: return@launch
            try {
                withContext(ioDispatcher) {
                    Files.copy(source.toPath(), File(directory, chosen).toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                log.i { "attachment saved blobId=$blobId" }
            } catch (error: Exception) {
                log.w(error) { "failed to save attachment blobId=$blobId" }
            }
        }
    }

    private fun cachedFileFor(blobId: String): File? =
        knownRefs[blobId]?.let { attachmentCache.cachedFile(it) }

    /**
     * 保持するリソース（トーストコルーチンと HTTP クライアント）を閉じる。
     * 進行中のトーストジョブ（dismiss/mute 等の JSONL 追記を含む）の完了を待ってから HTTP を閉じる。
     */
    suspend fun close() {
        toastJob.cancelAndJoin()
        httpClient.close()
    }

    private companion object {
        /** サムネイルデコードを試みる添付の上限バイト（巨大画像で OOM しないため）。 */
        const val MAX_THUMBNAIL_DECODE_BYTES: Long = 25L * 1024 * 1024
    }
}
