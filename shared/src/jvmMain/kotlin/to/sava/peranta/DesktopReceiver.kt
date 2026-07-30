package to.sava.peranta

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import co.touchlab.kermit.Logger
import com.russhwolf.settings.PreferencesSettings
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
import kotlinx.coroutines.withTimeoutOrNull
import to.sava.peranta.blob.AttachmentOpenDecision
import to.sava.peranta.blob.AutoFetchRole
import to.sava.peranta.blob.DesktopAttachmentCache
import to.sava.peranta.blob.KtorBlobTransport
import to.sava.peranta.blob.MAX_THUMBNAIL_DECODE_BYTES
import to.sava.peranta.blob.TransferProgress
import to.sava.peranta.blob.TransferState
import to.sava.peranta.blob.attachmentOpenDecision
import to.sava.peranta.blob.decodeImageWithinPixelLimit
import to.sava.peranta.blob.exceedsFullTextAutoFetchLimit
import to.sava.peranta.blob.shouldAutoFetch
import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.config.isDevMode
import to.sava.peranta.config.timelineRetentionMaxAgeMillis
import to.sava.peranta.config.withDevOverrides
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.Payload
import to.sava.peranta.model.SwipeBehavior
import to.sava.peranta.model.notificationKeyOrNull
import to.sava.peranta.model.swipeBehaviorOrDefault
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.net.KtorNtfyClient
import to.sava.peranta.net.SelfTestProbe
import to.sava.peranta.net.SelfTestStatus
import to.sava.peranta.net.createNtfyHttpClient
import to.sava.peranta.net.httpBaseUrl
import to.sava.peranta.pairing.SettingsController
import to.sava.peranta.platform.ioDispatcher
import to.sava.peranta.receive.LocalDismissCommandExecutor
import to.sava.peranta.receive.ReceivePipeline
import to.sava.peranta.send.CommandSender
import to.sava.peranta.send.SendPipeline
import to.sava.peranta.roster.PresenceAnnounceGate
import to.sava.peranta.roster.PresenceAnnounceScheduler
import to.sava.peranta.roster.RosterStore
import to.sava.peranta.roster.buildPresencePayload
import to.sava.peranta.roster.presenceCapabilities
import to.sava.peranta.roster.presenceFingerprint
import to.sava.peranta.roster.publishPresence
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.JsonlTimelineStore
import to.sava.peranta.timeline.ReceivedFile
import to.sava.peranta.timeline.ReceivedMessage
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineFeed
import to.sava.peranta.timeline.TimelineItem
import to.sava.peranta.timeline.defaultTimelineFile
import to.sava.peranta.toast.ReceivedNotificationToast
import to.sava.peranta.toast.ToastResult
import to.sava.peranta.toast.Toaster
import to.sava.peranta.toast.toastContentFor
import to.sava.peranta.ui.AppFilterController
import to.sava.peranta.ui.AttachmentDownloadState
import to.sava.peranta.ui.AttachmentUi
import to.sava.peranta.ui.FullTextUi
import to.sava.peranta.ui.displayAttachments
import to.sava.peranta.ui.referencedAttachments
import to.sava.peranta.ui.senderIcon
import to.sava.peranta.ui.MessageComposerUi
import to.sava.peranta.ui.TimelineActions
import to.sava.peranta.ui.shell.RosterUi
import to.sava.peranta.window.WindowGeometryStore
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.prefs.Preferences
import kotlin.io.encoding.Base64

/**
 * [repository] の永続化状態から Desktop 実行に必要な項目を補って設定を組み立てる。
 * [devMode] が真のときだけ開発用オーバーライド（[withDevOverrides]）を適用し、
 * 偽のとき（配布物）はオーバーライドを一切適用せず TLS を常に有効化する（§16）。
 * 端末名があれば安定 deviceId を確定し、受信 topic 未設定なら topic を採番・永続化する。
 */
private fun enrichConfig(repository: ConfigRepository, devMode: Boolean): PerantaConfig {
    val config = if (devMode) repository.load().withDevOverrides() else repository.load()
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
 * Desktop の設定を置く Preferences ノードのパス（§11）。
 * `Preferences.userRoot()` 直下はこの JVM で動く全アプリが共有するため、
 * キー名の衝突を避けて専用ノードに分ける。
 */
private const val SETTINGS_NODE_PATH: String = "to/sava/peranta"

/** Desktop の設定ストア。 */
fun desktopSettings(): Settings = PreferencesSettings(Preferences.userRoot().node(SETTINGS_NODE_PATH))

/**
 * Desktop の設定を settings から読む。[enrichConfig] に委譲する薄いエントリポイント。
 */
fun loadDesktopConfig(
    settings: Settings = desktopSettings(),
    devMode: Boolean = isDevMode(),
): PerantaConfig = enrichConfig(desktopConfigRepository(settings, devMode), devMode)

/**
 * Desktop 起動時の設定読み込みと、設定画面コントローラを同一の settings 実体から作る。
 * これにより desktopApp 側は multiplatform-settings の型に依存せず設定 UI を配線できる。
 */
class DesktopSettings(
    settings: Settings = desktopSettings(),
    val devMode: Boolean = isDevMode(),
) {
    /** 設定ストアに紐づくリポジトリ。設定画面・アプリフィルタ画面の永続化で共有する。 */
    val repository: ConfigRepository = desktopConfigRepository(settings, devMode)

    /** ウィンドウの見え方の記憶（§11）。設定と同じストアに保存する。 */
    val windowGeometry: WindowGeometryStore = WindowGeometryStore(settings)

    /** 起動時に一度だけ読み込んだ設定。初期表示の判定に使う。 */
    val config: PerantaConfig = enrichConfig(repository, devMode)
    val controller: SettingsController = SettingsController(repository)

    /** 現在の永続化状態から enrichment 済みの設定を読み直す（設定変更の自動反映に使う）。 */
    fun reloadConfig(): PerantaConfig = enrichConfig(repository, devMode)
}

/** 稼働中の Desktop 受信機が公開する自己疎通テストの操作口（§10.5）。 */
interface DesktopSelfTest {
    /** プローブの現在状態。動作チェックの項目描画がこれを読む。 */
    val selfTestStatus: StateFlow<SelfTestStatus>

    /** テストを非同期で開始する（実行中は何もしない）。 */
    fun startSelfTest()
}

/**
 * Desktop 受信の中核を組み立てる。設定が揃っている（[PerantaConfig.isReadyForReceive]）
 * 前提で生成すること。受信通知はトーストにも表示する（[toaster]）。
 * [repository] はアプリフィルタ画面（§10.4-1）のローカルミラー永続化と共有する設定リポジトリ。
 */
class DesktopReceiver(
    val config: PerantaConfig,
    private val repository: ConfigRepository,
    private val toaster: Toaster,
    private val onToastClicked: (itemId: String) -> Unit = {},
    private val log: Logger = Logger.withTag("DesktopReceiver"),
) : DesktopSelfTest {
    private val httpClient = createNtfyHttpClient()
    private val feed = TimelineFeed(JsonlTimelineStore(defaultTimelineFile()))
    private val cipher = MessageCipher(Base64.decode(config.sharedKeyBase64!!), config.keyId!!)
    private val ntfy = KtorNtfyClient(config, httpClient, Logger.withTag("NtfyClient"))
    private val toastJob = SupervisorJob()
    private val toastScope = CoroutineScope(toastJob + ioDispatcher)
    private val sendPipeline = SendPipeline(cipher, ntfy, feed)
    private val commandSender = CommandSender(config, cipher, ntfy, sendPipeline)
    private val composer by lazy { DesktopComposer(config, httpClient, cipher, ntfy, sendPipeline, toastScope) }
    private val announceGate = PresenceAnnounceGate()
    private val presenceScheduler = PresenceAnnounceScheduler<Unit>(toastScope) { announcePresence() }
    private val selfTestProbe = SelfTestProbe()

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
        feed = feed,
        deviceId = config.deviceId!!,
        commandExecutor = dismissExecutor,
        persistSensitiveHistory = config.persistSensitiveHistory,
        onItemAppended = ::handleAppended,
        onItemUpdated = ::handleUpdated,
        interceptRawMessage = selfTestProbe::consumeMarker,
    )

    /** UI が購読するタイムライン。 */
    val items: StateFlow<List<TimelineItem>> = pipeline.items

    override val selfTestStatus: StateFlow<SelfTestStatus> get() = selfTestProbe.status

    override fun startSelfTest() {
        toastScope.launch { selfTestProbe.run(ntfy, config.receiveTopic!!) }
    }

    private fun currentItems(): List<TimelineItem> = pipeline.items.value

    /** 起動時剪定と presence 告知を行い、受信 topic の購読を開始する。キャンセルまで戻らない。 */
    suspend fun run() {
        feed.prune(now = nowEpochMillis(), maxAgeMillis = config.timelineRetentionMaxAgeMillis)
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
            items.flatMap { item -> item.referencedAttachments() }.forEach { ref ->
                knownRefs[ref.blobId] = ref
                if (attachmentStates.value[ref.blobId] == null) {
                    attachmentCache.cachedFile(ref)?.let { markCached(ref) }
                }
            }
        }
    }

    /**
     * control topic へ自端末の presence を告知する（§3.5）。起動時と受信のたびに呼ぶ。
     * control topic 未設定なら何もしない。失敗しても受信開始は妨げない。
     * 同一内容の連続 announce は [PresenceAnnounceGate] が最小間隔で抑止する。
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
                // 受信中は通知を表示できる。元通知への操作は通知捕捉（NLS）を持たない Desktop では
                // 実行できないためコマンド能力は持たない（自表示通知の取り下げは表示能力に含む、§3.5）。
                capabilities = presenceCapabilities(canDisplay = true, canCommand = false),
                sender = config.sendEnabled,
                now = nowEpochMillis(),
            )
            val fingerprint = presenceFingerprint(presence)
            if (!announceGate.shouldAnnounce(fingerprint, presence.sentAtEpochMillis)) return
            publishPresence(cipher, ntfy, controlTopic, presence)
            announceGate.recordAnnounced(fingerprint, presence.sentAtEpochMillis)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log.w(error) { "presence announce failed" }
        }
    }

    /** タイムラインに載った新規アイテムをトースト表示へ回す（受信処理はブロックしない）。 */
    internal fun handleAppended(item: TimelineItem) {
        // 受信は自端末が動いている証。起動しっぱなしでは告知の機会が無く、control topic の
        // 保持期間を過ぎると presence が消えてロスターから落ちる（§3.5）。
        presenceScheduler.request(Unit)
        when (item) {
            is ReceivedNotification -> toastScope.launch { showNotificationToast(item) }
            is ErrorItem -> toastScope.launch { showToast(item.id, toastContentFor(item)) }
            is ReceivedFile -> toastScope.launch { showToast(item.id, toastContentFor(item)) }
            is ReceivedMessage -> toastScope.launch { showToast(item.id, toastContentFor(item)) }
            else -> Unit
        }
    }

    /**
     * 改版で差し替わった受信通知を表示へ反映する（§4.3.1）。後から届いた画像と送信者アイコンを取得し、
     * まだ表示中のトーストへ差し込む。タイムラインのバブルは items の更新で自動的に追随する。
     */
    internal fun handleUpdated(item: TimelineItem) {
        if (item !is ReceivedNotification) return
        toastScope.launch { updateToastImages(item) }
    }

    /**
     * 通知に後から付いた画像・送信者アイコンを取得し、表示中のトーストへ差し込む（§4.3.1）。
     * どちらも取得できなければ何もしない。トーストが既に消えていれば [Toaster.update] が空振りする。
     */
    private suspend fun updateToastImages(item: ReceivedNotification) {
        val image = fetchThumbnail(item.payload.toastImage(), AutoFetchRole.DISPLAY_IMAGE)
        val senderIcon = fetchThumbnail(item.payload.senderIcon(), AutoFetchRole.SENDER_ICON)
        if (image == null && senderIcon == null) return
        toastContentFor(item)?.let { toaster.update(it.copy(image = image, senderIcon = senderIcon)) }
    }

    /**
     * [ref] のサムネイルを返す。取得済みならそれを、未取得なら [shouldAutoFetch] が許すときだけ
     * ダウンロードを起こす。参照が無い・自動取得の対象外・取得やデコードに失敗した場合は null。
     *
     * 待ちには上限を置く（[TOAST_IMAGE_WAIT_MILLIS]）。トーストは速報として先に出ており、
     * 取得が長引く間ここで待ち続けると差し込み以降の更新が止まる。待ちを打ち切っても
     * ダウンロード自体は続き、タイムラインの添付カードには反映される。
     */
    private suspend fun fetchThumbnail(ref: AttachmentRef?, role: AutoFetchRole): ImageBitmap? {
        if (ref == null) return null
        val state = attachmentStates.value[ref.blobId]
        state?.thumbnail?.let { return it }
        val running = downloadJobs[ref.blobId]?.takeIf { it.isActive }
        val job = running ?: run {
            val autoFetch = shouldAutoFetch(
                ref = ref,
                role = role,
                autoDisplayImages = config.autoDisplayImages,
                now = nowEpochMillis(),
                alreadyFetched = state?.cached == true,
                transferStarted = state?.progress != null,
            )
            if (!autoFetch) return null
            startDownload(ref)
        }
        withTimeoutOrNull(TOAST_IMAGE_WAIT_MILLIS) { job.join() }
        return attachmentStates.value[ref.blobId]?.thumbnail
    }

    /** 取得済みのサムネイルだけを添える。初回表示を待たせないため、ここではダウンロードを起こさない。 */
    private fun ReceivedNotificationToast.withCachedImages(item: ReceivedNotification): ReceivedNotificationToast {
        val image = item.payload.toastImage()?.let { attachmentStates.value[it.blobId]?.thumbnail }
        val senderIcon = item.payload.senderIcon()?.let { attachmentStates.value[it.blobId]?.thumbnail }
        return copy(image = image, senderIcon = senderIcon)
    }

    /**
     * [itemId] のタイムライン上の現在のペイロード。トーストは表示したまま改版で差し替わることが
     * あるため（§3.1 の SMS の対応づけ等）、ボタン押下は表示時ではなく押された時点の内容で扱う。
     */
    private fun currentPayload(itemId: String): Payload? =
        pipeline.items.value.asSequence()
            .filterIsInstance<ReceivedNotification>()
            .firstOrNull { it.id == itemId }
            ?.payload

    private suspend fun showNotificationToast(item: ReceivedNotification) {
        val content = toastContentFor(item)?.withCachedImages(item) ?: return
        when (toaster.show(content)) {
            ToastResult.ButtonDismiss -> requestDismiss(currentPayload(item.id) ?: item.payload)
            ToastResult.ButtonOpen -> content.openUrl?.let { openUrlInBrowser(it) }
            ToastResult.Clicked -> {
                log.i { "toast clicked id=${item.id}" }
                onToastClicked(item.id)
            }

            ToastResult.Dismissed -> dismissSourceOnSwipe(currentPayload(item.id) ?: item.payload)

            else -> Unit
        }
    }

    /**
     * トーストを払いのけた（スワイプ・× ボタン）ときの扱いを、送信端末が載せた指示（§3.3 / §7）で
     * 決める。既定はこの端末の表示を引っ込めるだけで、指示があるときだけ既読同期も発火する。
     */
    private fun dismissSourceOnSwipe(payload: Payload) {
        if (payload.swipeBehaviorOrDefault() != SwipeBehavior.DISMISS_SOURCE) return
        requestDismiss(payload)
    }

    /**
     * 受信ファイル・受信メッセージ・エラーのトーストを表示し、クリックのみ前面化＋該当アイテムへの
     * スクロールへ配線する（§3.3）。「送信元の通知を消す」押下は送るべき既読同期コマンドを持たないため
     * 何もしない。
     */
    private suspend fun showToast(itemId: String, content: ReceivedNotificationToast) {
        if (toaster.show(content) == ToastResult.Clicked) {
            log.i { "toast clicked id=$itemId" }
            onToastClicked(itemId)
        }
    }

    /** トーストの「開く」ボタン押下で本文中の URL を既定ブラウザで開く（受信端末ローカル、§3.3）。 */
    private fun openUrlInBrowser(url: String) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            log.w { "Desktop browse not supported" }
            return
        }
        try {
            Desktop.getDesktop().browse(URI(url))
        } catch (error: Exception) {
            log.w(error) { "failed to open url from toast" }
        }
    }

    /**
     * トーストの「送信元の通知を消す」押下時に既読同期（§3.4）の dismiss を全端末へブロードキャストし、
     * 自端末のタイムラインアイテムも sourceDismissed としてマークする（ブロードキャストは
     * 自端末を除外するため、自分での操作は自分で反映する必要がある）。
     * 元通知に紐づかない（notificationKey を持たない）アイテムは取り下げ対象にならないためログのみとする。
     */
    private fun requestDismiss(payload: Payload) {
        val notificationKey = payload.notificationKeyOrNull() ?: run {
            log.i { "dismiss ignored (no notification key) payload=${payload.id}" }
            return
        }
        toastScope.launch {
            commandSender.dismiss(notificationKey)
            pipeline.markSourceDismissed(notificationKey)
        }
    }

    /**
     * タイムライン UI 用の操作束を作る（§10.1）。アクション発火・返信・非表示は送信元へ一点指定、
     * 「送信元の通知を消す」は既読同期のため全端末へブロードキャストしつつ、表示済みトーストも取り下げる。
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
        reply = { payload, index, text ->
            toastScope.launch {
                commandSender.reply(payload.from, payload.notificationKey, index, text)
            }
        },
        hideFromTimeline = { item -> toastScope.launch { pipeline.hideFromTimeline(item.id) } },
        dismissAll = { items -> dismissAllFromTimeline(items) },
    )

    /**
     * 元通知が生きている通知をまとめて消す（§10.1）。同じ元通知を指すアイテムが複数あっても
     * コマンドは 1 通知につき 1 回にまとめ、表示済みトーストはアイテムごとに取り下げる。
     */
    private fun dismissAllFromTimeline(items: List<ReceivedNotification>) {
        toastScope.launch {
            items.forEach { toaster.close(it.payload.id) }
            items.mapNotNull { it.payload.notificationKeyOrNull() }.distinct().forEach { notificationKey ->
                commandSender.dismiss(notificationKey)
                pipeline.markSourceDismissed(notificationKey)
            }
        }
    }

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

    /**
     * composer からの送信操作束を組む（§5.2・§13 M9d）。送信設定が揃っていなければ null（composer 非表示）。
     * ファイル添付束は blob topic が設定されているときのみ付く。ステージ済みファイルは [composer] とともに
     * 保持され、受信機の再生成（設定変更）で消える。
     */
    fun composerUi(): MessageComposerUi? = if (config.isReadyForSend) composer.ui() else null

    /** 参加端末一覧ドロップダウンの取得口を組む（§3.5）。control topic 未設定なら null。 */
    fun rosterUi(): RosterUi? {
        val controlTopic = config.controlTopic ?: return null
        return RosterUi(
            selfDeviceId = config.deviceId,
            fetch = { RosterStore(ntfy, cipher, controlTopic).fetch() },
        )
    }

    private fun dismissFromTimeline(item: ReceivedNotification) {
        toastScope.launch {
            toaster.close(item.payload.id)
            val notificationKey = item.payload.notificationKeyOrNull() ?: run {
                log.i { "dismiss ignored (no notification key) payload=${item.payload.id}" }
                return@launch
            }
            commandSender.dismiss(notificationKey)
            pipeline.markSourceDismissed(notificationKey)
        }
    }

    /**
     * タイムラインの添付カード用の操作束を作る（§4.3）。ダウンロード・キャンセル・開く・保存を配線し、
     * 進捗は [attachmentStates] を通じて画面へ公開する。画像の自動表示可否は設定の
     * [to.sava.peranta.config.PerantaConfig.autoDisplayImages] をそのまま渡す。
     */
    fun attachmentUi(): AttachmentUi = AttachmentUi(
        states = attachmentStates.asStateFlow(),
        onDownload = { ref -> startDownload(ref) },
        onCancel = { blobId -> cancelDownload(blobId) },
        onOpen = { blobId -> openAttachment(blobId) },
        onSave = { blobId -> saveAttachment(blobId) },
        autoDisplayImages = config.autoDisplayImages,
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
            // 例外そのものは流さない。ktor の例外メッセージには blob の取得先 URL（＝ホスト）が載る（§16）。
            log.w { "full text fetch failed blobId=${ref.blobId} (${error::class.simpleName})" }
            null
        }
    }

    /**
     * [ref] の添付をダウンロードする。既に進行中ならそのジョブを返し、二重起動しない。
     * 戻り値は完了を待てるジョブで、トーストへの画像差し込み（§4.3.1）が取得完了を待つのに使う。
     */
    private fun startDownload(ref: AttachmentRef): Job {
        knownRefs[ref.blobId] = ref
        downloadJobs[ref.blobId]?.takeIf { it.isActive }?.let { return it }
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
                // 例外そのものは流さない。ktor の例外メッセージには blob の取得先 URL（＝ホスト）が載る（§16）。
                log.w { "attachment download failed blobId=${ref.blobId} (${error::class.simpleName})" }
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
        return job
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

    /**
     * 画像添付を復号済みファイルからデコードしてサムネイルにする。失敗時は null（種別アイコンにフォールバック）。
     * 符号化サイズと展開後の画素数の双方に上限を掛ける（[decodeImageWithinPixelLimit]）。
     */
    private fun decodeThumbnail(ref: AttachmentRef, file: File): ImageBitmap? {
        if (ref.kind != AttachmentKind.IMAGE) return null
        if (file.length() > MAX_THUMBNAIL_DECODE_BYTES) return null
        return try {
            decodeImageWithinPixelLimit(file.readBytes())?.toComposeImageBitmap()
        } catch (error: Exception) {
            log.w(error) { "thumbnail decode failed blobId=${ref.blobId}" }
            null
        }
    }

    /**
     * 復号済みファイルを OS 既定アプリで開く（§4.3）。
     * 添付カードは [attachmentOpenDecision] で導線を出し分けているが、OS へ渡す直前でも当て直す。
     */
    private fun openAttachment(blobId: String) {
        val ref = knownRefs[blobId] ?: return
        if (attachmentOpenDecision(ref.mimeType, ref.fileName) == AttachmentOpenDecision.REFUSE) {
            log.w { "attachment open refused blobId=$blobId" }
            return
        }
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
        /** トーストへ画像を差し込むために取得完了を待つ上限。 */
        const val TOAST_IMAGE_WAIT_MILLIS: Long = 10_000
    }
}

/** トーストへ差し込む本文画像の参照（§4.3.1）。画像添付が無ければ null。 */
private fun Payload.toastImage(): AttachmentRef? =
    displayAttachments().firstOrNull { it.kind == AttachmentKind.IMAGE }
