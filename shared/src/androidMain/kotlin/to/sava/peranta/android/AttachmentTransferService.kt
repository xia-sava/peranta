package to.sava.peranta.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import co.touchlab.kermit.Logger
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import to.sava.peranta.blob.AttachmentUploadRequest
import to.sava.peranta.blob.BlobCipher
import to.sava.peranta.blob.KtorBlobTransport
import to.sava.peranta.blob.attachmentKindForMimeType
import to.sava.peranta.blob.decodeAttachmentRef
import to.sava.peranta.blob.encodeAttachmentRef
import to.sava.peranta.blob.uploadAttachment
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.newPayloadId
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.net.createNtfyHttpClient
import to.sava.peranta.platform.ioDispatcher
import to.sava.peranta.send.buildFilePayloads
import to.sava.peranta.shared.R
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ErrorKind
import java.io.File
import java.io.FilterInputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.encoding.Base64

/**
 * 共有された画像・ファイルを暗号化して blobTopic へアップロードし、[FilePayload] として配送する
 * フォアグラウンドサービス（§4.3）。300MB 級でも WorkManager の実行時間制限を避けるため FGS を使う。
 * 転送ごとに独立したジョブ・進捗通知（キャンセルアクション付き）を持ち、ある転送の完了・キャンセルが
 * 他の転送を巻き添えにしないようにする。進行中の転送が無くなるまでサービスを止めない。
 * 進捗は転送ごとの専用チャネル（IMPORTANCE_LOW）に表示する。
 * blob アップロード失敗は自動再送せず、タイムラインにエラーを残してユーザーの手動再試行に委ねる。
 */
class AttachmentTransferService : Service() {

    private val log = Logger.withTag("AttachmentTransfer")
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val httpClient by lazy { createNtfyHttpClient() }
    private val manager: NotificationManager by lazy { getSystemService(NotificationManager::class.java) }

    private val registry = TransferRegistry()
    private val notificationCounter = AtomicInteger(PROGRESS_NOTIFICATION_ID_BASE)

    /** 直近に受け取った startId。全転送が終わったときに [Service.stopSelf] へ渡す。 */
    @Volatile
    private var latestStartId: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_CANCEL -> cancelTransfer(intent.getStringExtra(EXTRA_TRANSFER_ID))
            ACTION_UPLOAD -> startUpload(intent)
            ACTION_DOWNLOAD -> startDownload(intent)
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun startUpload(intent: Intent) {
        val transferId = newPayloadId()
        val caption = intent.getStringExtra(EXTRA_CAPTION)
        val uris = intent.uris()
        val notificationId = notificationCounter.getAndIncrement()
        createChannel()
        // startForegroundService の起動要件を満たすため、まず umbrella 通知で前面化する。
        startUmbrellaForeground()
        if (uris.isEmpty()) {
            stopIfIdle()
            return
        }
        manager.notify(notificationId, progressNotification(transferId, fileName = "", percent = 0, uploading = true))
        val job = scope.launch {
            try {
                process(transferId, notificationId, uris, caption)
            } catch (cancellation: CancellationException) {
                log.i { "upload job cancelled transferId=$transferId" }
            } catch (error: Exception) {
                // 例外そのものは流さない。ktor の例外メッセージには blob の送り先 URL（＝ホスト）が載る（§16）。
                log.w { "attachment upload failed transferId=$transferId (${error::class.simpleName})" }
                recordError(UPLOAD_FAILED_MESSAGE)
            } finally {
                finishTransfer(transferId)
            }
        }
        registry.register(transferId, notificationId, job)
    }

    private fun startDownload(intent: Intent) {
        val encodedRef = intent.getStringExtra(EXTRA_ATTACHMENT_REF)
        val ref = encodedRef?.let {
            try {
                decodeAttachmentRef(it)
            } catch (error: Exception) {
                // 例外そのものは流さない。復号の例外は入力そのもの（blob の URL を含む）を説明文へ載せる（§16）。
                log.w { "failed to decode attachment ref for download (${error::class.simpleName})" }
                null
            }
        }
        createChannel()
        startUmbrellaForeground()
        if (ref == null) {
            stopIfIdle()
            return
        }
        // 同一 blob の二重ダウンロードを避ける（進行中ならボタンは既に進捗表示に切り替わっている）。
        if (registry.contains(ref.blobId)) {
            log.i { "download already in progress blobId=${ref.blobId}" }
            return
        }
        val notificationId = notificationCounter.getAndIncrement()
        manager.notify(notificationId, progressNotification(ref.blobId, ref.fileName, percent = 0, uploading = false))
        val job = scope.launch {
            try {
                processDownload(ref, notificationId)
            } catch (cancellation: CancellationException) {
                log.i { "download job cancelled blobId=${ref.blobId}" }
                AndroidAttachmentReceive.markCancelled(ref.blobId)
            } catch (error: Exception) {
                // 例外そのものは流さない。ktor の例外メッセージには blob の取得先 URL（＝ホスト）が載る（§16）。
                log.w { "attachment download failed blobId=${ref.blobId} (${error::class.simpleName})" }
                AndroidAttachmentReceive.markFailed(ref.blobId, ref.sizeBytes)
                recordError(DOWNLOAD_FAILED_MESSAGE)
            } finally {
                finishTransfer(ref.blobId)
            }
        }
        registry.register(ref.blobId, notificationId, job)
    }

    private suspend fun processDownload(ref: AttachmentRef, notificationId: Int) = coroutineScope {
        val repo = androidConfigRepository(applicationContext)
        val config = repo.load()
        if (!config.hasSharedKey) {
            AndroidAttachmentReceive.markFailed(ref.blobId, ref.sizeBytes)
            recordError(NOT_CONFIGURED_DOWNLOAD_MESSAGE)
            return@coroutineScope
        }
        AndroidAttachmentReceive.markRunning(ref.blobId, ref.sizeBytes)
        val cache = AndroidAttachmentReceive.cache(applicationContext, config)
        val transferred = AtomicLong(0)
        // 進捗通知はアップロードと同じくポーリングで間引く（コールバック毎に notify すると数千回叩いてしまう）。
        val pollJob = launch {
            while (isActive) {
                val percent = percentOf(ref.sizeBytes, transferred.get())
                manager.notify(notificationId, progressNotification(ref.blobId, ref.fileName, percent, uploading = false))
                if (percent >= 100) break
                delay(PROGRESS_POLL_MILLIS)
            }
        }
        val file = try {
            cache.download(ref) { current ->
                transferred.set(current)
                AndroidAttachmentReceive.markProgress(ref.blobId, current, ref.sizeBytes)
            }
        } finally {
            pollJob.cancel()
        }
        AndroidAttachmentReceive.markCached(ref, file)
        log.i { "attachment downloaded blobId=${ref.blobId}" }
    }

    private fun cancelTransfer(transferId: String?) {
        val job = transferId?.let { registry.jobOf(it) }
        if (job == null) {
            log.i { "cancel ignored: no active transfer" }
            stopIfIdle()
            return
        }
        log.i { "upload cancelled by user transferId=$transferId" }
        // ジョブ完了時の finally が台帳・通知・停止を処理する。
        job.cancel()
    }

    /** 転送 1 件の後片付け。進捗通知を消し、台帳から外し、全転送が終わっていればサービスを止める。 */
    private fun finishTransfer(transferId: String) {
        registry.notificationIdOf(transferId)?.let { manager.cancel(it) }
        if (registry.remove(transferId)) stopService()
    }

    private fun stopIfIdle() {
        if (registry.isEmpty()) stopService()
    }

    private fun stopService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(latestStartId)
    }

    private suspend fun process(
        transferId: String,
        notificationId: Int,
        uris: List<Uri>,
        caption: String?,
    ) {
        val repo = androidConfigRepository(applicationContext)
        val config = repo.load().copy(deviceId = repo.ensureDeviceId())
        if (!config.isReadyForSend) {
            recordError(NOT_CONFIGURED_MESSAGE)
            return
        }
        val blobTopic = config.blobTopic ?: repo.ensureBlobTopic()
        val cipher = BlobCipher(Base64.decode(config.sharedKeyBase64!!), config.keyId!!)
        val transport = KtorBlobTransport(config, httpClient)

        val attachments = uris.mapNotNull { uri ->
            uploadOne(transferId, notificationId, uri, cipher, transport, blobTopic)
        }
        if (attachments.isEmpty()) {
            recordError(UPLOAD_FAILED_MESSAGE)
            return
        }
        // 複数添付の Envelope が UnifiedPush の実質上限を超えないよう、収まる範囲で複数ペイロードに分割する（§4.3）。
        val payloads = buildFilePayloads(
            deviceId = config.deviceId!!,
            attachments = attachments,
            keyId = config.keyId,
            now = nowEpochMillis(),
            caption = caption,
            deviceName = config.deviceName,
        )
        payloads.forEach { payload -> PerantaSend.dispatch(applicationContext, payload, config) }
        log.i {
            "file payloads dispatched transferId=$transferId attachments=${attachments.size} payloads=${payloads.size}"
        }
    }

    private suspend fun uploadOne(
        transferId: String,
        notificationId: Int,
        uri: Uri,
        cipher: BlobCipher,
        transport: KtorBlobTransport,
        blobTopic: String,
    ): AttachmentRef? = coroutineScope {
        val spool = spool(transferId, uri) ?: return@coroutineScope null
        try {
            val meta = attachmentMeta(uri, spool)
            val transferred = AtomicLong(0)
            val pollJob = launch {
                while (isActive) {
                    val percent = percentOf(meta.sizeBytes, transferred.get())
                    manager.notify(notificationId, progressNotification(transferId, meta.fileName, percent, uploading = true))
                    if (percent >= 100) break
                    delay(PROGRESS_POLL_MILLIS)
                }
            }
            try {
                uploadAttachment(
                    transport = transport,
                    blobCipher = cipher,
                    blobTopic = blobTopic,
                    request = AttachmentUploadRequest(
                        fileName = meta.fileName,
                        mimeType = meta.mimeType,
                        sizeBytes = meta.sizeBytes,
                        kind = kindFor(meta.mimeType),
                        openSource = { countingChannel(spool, transferred) },
                    ),
                )
            } finally {
                pollJob.cancel()
            }
        } finally {
            if (!spool.delete()) log.w { "failed to delete spool ${spool.name}" }
        }
    }

    /** アップロード対象を専用キャッシュ領域へコピーし、権限維持とサイズ確定を行う。失敗時は null。 */
    private fun spool(transferId: String, uri: Uri): File? =
        try {
            val dir = File(cacheDir, OUTGOING_CACHE_DIR).apply { mkdirs() }
            val target = File.createTempFile("upload", ".bin", dir)
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                target.delete()
                return null
            }
            target
        } catch (error: Exception) {
            log.w(error) { "failed to spool attachment transferId=$transferId" }
            null
        }

    private fun attachmentMeta(uri: Uri, spool: File): AttachmentMeta = AttachmentMeta(
        fileName = sharedStreamDisplayName(uri),
        mimeType = contentResolver.getType(uri) ?: DEFAULT_IMAGE_MIME,
        sizeBytes = spool.length(),
    )

    /** カウント付き入力ストリームから blob 本体のチャンネルを開く。読み取りバイト数を [transferred] に反映する。 */
    private fun countingChannel(spool: File, transferred: AtomicLong): ByteReadChannel {
        transferred.set(0)
        val counting = object : FilterInputStream(spool.inputStream()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { if (it > 0) transferred.addAndGet(it.toLong()) }
        }
        return counting.toByteReadChannel()
    }

    private fun percentOf(totalBytes: Long, transferredBytes: Long): Int =
        if (totalBytes <= 0) 100 else ((transferredBytes * 100) / totalBytes).toInt().coerceIn(0, 100)

    private fun startUmbrellaForeground() {
        val notification = umbrellaNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(UMBRELLA_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(UMBRELLA_NOTIFICATION_ID, notification)
        }
    }

    /**
     * サービスの生存期間中だけ出す前面化用の通知（個々の転送はこれとは別の進捗通知で表す）。
     * アップロード・ダウンロードの両方を束ねるため、方向を持たないアプリアイコンを使う。
     */
    private fun umbrellaNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(UMBRELLA_TITLE)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun progressNotification(transferId: String, fileName: String, percent: Int, uploading: Boolean): Notification {
        val verb = if (uploading) "アップロード中" else "ダウンロード中"
        val fallbackTitle = if (uploading) UPLOAD_TITLE else DOWNLOAD_TITLE
        val icon = if (uploading) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(if (fileName.isBlank()) fallbackTitle else "$fileName を$verb $percent%")
            .setSmallIcon(icon)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, percent == 0)
            .addAction(
                Notification.Action.Builder(null, CANCEL_LABEL, cancelIntent(transferId)).build(),
            )
            .build()
    }

    private fun cancelIntent(transferId: String): PendingIntent {
        val intent = Intent(this, AttachmentTransferService::class.java)
            .setAction(ACTION_CANCEL)
            .putExtra(EXTRA_TRANSFER_ID, transferId)
        return PendingIntent.getService(
            this,
            transferId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW),
        )
    }

    private suspend fun recordError(message: String) {
        runCatching {
            PerantaSend.timelineFeed.append(
                ErrorItem(
                    id = newPayloadId(),
                    timestampEpochMillis = nowEpochMillis(),
                    message = message,
                    kind = ErrorKind.OTHER,
                ),
            )
        }.onFailure { log.w(it) { "failed to record upload error" } }
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { httpClient.close() }
        super.onDestroy()
    }

    private fun kindFor(mimeType: String): AttachmentKind = attachmentKindForMimeType(mimeType)

    private data class AttachmentMeta(val fileName: String, val mimeType: String, val sizeBytes: Long)

    @Suppress("DEPRECATION")
    private fun Intent.uris(): List<Uri> {
        val single = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_STREAM)
        }
        single?.let { return listOf(it) }
        val multiple = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }
        return multiple.orEmpty()
    }

    companion object {
        private const val ACTION_UPLOAD = "to.sava.peranta.action.UPLOAD"
        private const val ACTION_DOWNLOAD = "to.sava.peranta.action.DOWNLOAD"
        private const val ACTION_CANCEL = "to.sava.peranta.action.CANCEL_TRANSFER"
        private const val EXTRA_CAPTION = "caption"
        private const val EXTRA_TRANSFER_ID = "transferId"
        private const val EXTRA_ATTACHMENT_REF = "attachmentRef"
        private const val CHANNEL_ID = "peranta-attachment-transfer"
        private const val CHANNEL_NAME = "添付の転送"
        private const val UMBRELLA_TITLE = "ファイルを転送中"
        private const val UPLOAD_TITLE = "画像をアップロード中"
        private const val DOWNLOAD_TITLE = "ファイルをダウンロード中"
        private const val CANCEL_LABEL = "キャンセル"
        private const val DEFAULT_IMAGE_MIME = "image/*"
        private const val CLIP_LABEL = "peranta-attachments"
        private const val PROGRESS_POLL_MILLIS = 500L

        /** サービス生存中だけ出す前面化用 umbrella 通知 ID（送信再送ワーカー 4201 と衝突しない）。 */
        private const val UMBRELLA_NOTIFICATION_ID = 4301

        /** 転送ごとの進捗通知 ID の起点（umbrella と重ならないよう十分離す）。 */
        private const val PROGRESS_NOTIFICATION_ID_BASE = 4310

        private const val UPLOAD_FAILED_MESSAGE = "画像のアップロードに失敗しました。もう一度お試しください"
        private const val NOT_CONFIGURED_MESSAGE = "送信の設定が未完了のため画像を送れません"
        private const val DOWNLOAD_FAILED_MESSAGE = "ファイルのダウンロードに失敗しました。もう一度お試しください"
        private const val NOT_CONFIGURED_DOWNLOAD_MESSAGE = "設定が未完了のためファイルを受け取れません"

        /**
         * 共有された [uris] のアップロードをサービスに依頼する（§4.3）。
         * URI の読み取り権限は Intent の [ClipData] に載せてサービスへ付与する（サービスの生存期間中有効）。
         * これにより共有元 Activity が finish しても、サービス側のスプールコピーが権限失効に巻き込まれない。
         */
        fun enqueueUpload(context: Context, uris: List<Uri>, caption: String?) {
            if (uris.isEmpty()) return
            val intent = Intent(context, AttachmentTransferService::class.java).apply {
                action = ACTION_UPLOAD
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = clipDataFor(uris)
                if (uris.size == 1) {
                    putExtra(Intent.EXTRA_STREAM, uris.single())
                } else {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                }
                putExtra(EXTRA_CAPTION, caption)
            }
            context.startForegroundService(intent)
        }

        /** 共有された全 URI を [ClipData] にまとめ、読み取り権限をサービスへ伝播できるようにする。 */
        private fun clipDataFor(uris: List<Uri>): ClipData {
            val clip = ClipData.newRawUri(CLIP_LABEL, uris.first())
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            return clip
        }

        /**
         * [ref] の添付ダウンロードをフォアグラウンドサービスへ依頼する（§4.3）。
         * 300MB 級でも WorkManager の実行時間制限を避けるため、アップロードと同じ FGS 経路を使う。
         * UI のボタンを即座に進行中表示へ切り替えるため、状態を先に進行中へ更新してから起動する。
         */
        fun enqueueDownload(context: Context, ref: AttachmentRef) {
            AndroidAttachmentReceive.markRunning(ref.blobId, ref.sizeBytes)
            val intent = Intent(context, AttachmentTransferService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_ATTACHMENT_REF, encodeAttachmentRef(ref))
                putExtra(EXTRA_TRANSFER_ID, ref.blobId)
            }
            context.startForegroundService(intent)
        }

        /** 進行中の転送（[transferId] はアップロードの転送 ID かダウンロードの blobId）をキャンセルする。 */
        fun cancel(context: Context, transferId: String) {
            val intent = Intent(context, AttachmentTransferService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_TRANSFER_ID, transferId)
            }
            context.startService(intent)
        }
    }
}
