package to.sava.peranta.android

import android.content.Context
import android.graphics.Bitmap
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import to.sava.peranta.blob.AttachmentUploadRequest
import to.sava.peranta.blob.BlobCipher
import to.sava.peranta.blob.KtorBlobTransport
import to.sava.peranta.blob.uploadAttachment
import to.sava.peranta.blob.uploadFullTextAttachment
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.config.timelineRetentionMaxAgeMillis
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.net.KtorNtfyClient
import to.sava.peranta.net.createNtfyHttpClient
import to.sava.peranta.platform.ioDispatcher
import to.sava.peranta.send.CommandSender
import to.sava.peranta.send.ForwardedKeyTracker
import to.sava.peranta.send.SendPipeline
import to.sava.peranta.send.UploadedAttachmentCache
import to.sava.peranta.send.attachFullTextIfNeeded
import to.sava.peranta.send.resolveSendTopics
import to.sava.peranta.send.shouldAttachNotificationImage
import to.sava.peranta.send.withImageAttachments
import to.sava.peranta.send.SmsDedupeTracker
import to.sava.peranta.send.NotificationRepostTracker
import to.sava.peranta.timeline.JsonlTimelineStore
import to.sava.peranta.timeline.TimelineFeed
import to.sava.peranta.timeline.defaultTimelineFile
import kotlin.io.encoding.Base64

/** 設定から共有鍵と keyId を取り出して [MessageCipher] を生成する。設定不足なら例外。 */
internal fun perantaCipher(config: PerantaConfig): MessageCipher {
    val keyBase64 = config.sharedKeyBase64 ?: error("shared key not configured")
    val keyId = config.keyId ?: error("keyId not configured")
    return MessageCipher(Base64.decode(keyBase64), keyId)
}

/**
 * Android 送信側のプロセス内シングルトン。
 * SMS の重複抑止トラッカーと通知更新トラッカーを NLS と SMS 受信で共有し、
 * HTTP クライアントとタイムラインストアを使い回す。
 */
object PerantaSend {

    private val log = Logger.withTag("PerantaSend")
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /** 直接受信 SMS と SMS アプリ通知の重複抑止（§3.1）。 */
    val dedupe = SmsDedupeTracker()

    /** 内容が変わらない通知再投稿の抑止（§3.1）。 */
    val reposts = NotificationRepostTracker()

    /** 自端末が転送した通知の key を覚え、元通知の削除検知で既読同期の要否を判定する（§3.4）。 */
    val forwarded = ForwardedKeyTracker()

    /** 同じ通知画像を再投稿のたびに上げ直さないための記憶（§4.3.1）。 */
    private val uploadedImages = UploadedAttachmentCache()

    private val httpClient by lazy { createNtfyHttpClient() }

    /** アプリ専用領域の JSONL タイムラインを覆う feed（プロセス内で共有、送受信・Service・Worker 全員が使う単一ソース）。 */
    val timelineFeed: TimelineFeed by lazy { TimelineFeed(JsonlTimelineStore(defaultTimelineFile())) }

    /**
     * ログの最小重大度を設定する（§16）。
     * [debuggable] が false（リリース）なら Info 以上のみ出力し、topic 名等の debug ログを抑止する。
     */
    fun configureLogging(debuggable: Boolean) {
        Logger.setMinSeverity(if (debuggable) Severity.Debug else Severity.Info)
    }

    /**
     * 起動時にタイムラインを剪定してから feed へ読み込む。失敗しても起動を妨げない。
     * 受信設定が未完了の送信専用端末では [PerantaReceive.prime] が走らないため、
     * ここで読み込んでおくことで履歴・送信済みアイテムをタイムラインに表示できるようにする。
     */
    fun primeTimelineInBackground() {
        scope.launch {
            try {
                val now = nowEpochMillis()
                val maxAgeMillis = androidConfigRepository().load().timelineRetentionMaxAgeMillis
                timelineFeed.prune(now = now, maxAgeMillis = maxAgeMillis)
                timelineFeed.load(now)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                log.w(error) { "timeline prime failed" }
            }
        }
    }

    /**
     * [payload] を即時送信し、失敗したら封筒と表示メタを WorkManager 再送に回す（§3.1）。
     * 各段階の例外は [SendPipeline.dispatch] が握るため、この関数は呼び出し側へ例外を漏らさない
     * （CancellationException を除く）。設定が不足していれば送信しない。
     * 戻り値は即時送信で配送できたか。false は「再送へ回した」または「失敗」を含む。
     */
    suspend fun dispatch(
        context: Context,
        payload: Payload,
        config: PerantaConfig,
        publishTimeoutMillis: Long? = null,
    ): Boolean {
        if (!config.isReadyForSend) {
            log.w { "send not configured; dropping payload id=${payload.id}" }
            return false
        }
        return try {
            val cipher = perantaCipher(config)
            val ntfy = KtorNtfyClient(config, httpClient)
            val pipeline = SendPipeline(cipher = cipher, ntfy = ntfy, store = timelineFeed)
            pipeline.dispatch(
                payload = payload,
                topics = resolveSendTopics(config, cipher, ntfy),
                persistSensitive = config.persistSensitiveHistory,
                publishTimeoutMillis = publishTimeoutMillis,
            ) { body, topics, cacheSeconds, meta ->
                SendRetryWorker.enqueue(context, body, topics, cacheSeconds, meta)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log.w(error) { "send dispatch setup failed for id=${payload.id}" }
            false
        }
    }

    /**
     * 長い本文の通知に全文添付を付ける（§4.3）。添付不要（トグル OFF・センシティブ・プレビュー予算内）なら
     * [payload] をそのまま返す。blob アップロード失敗時は例外を握って切り詰めプレビューのみで送る
     * （全文が失われても本文送信自体は退行させない）。
     */
    suspend fun withFullTextAttachment(
        context: Context,
        payload: NotificationPayload,
        fullText: String,
        config: PerantaConfig,
    ): NotificationPayload = augmentWithFullText(payload) {
        attachFullTextIfNeeded(
            payload = payload,
            fullText = fullText,
            attachFullTextWhenTruncated = config.attachFullTextWhenTruncated,
            persistSensitiveHistory = config.persistSensitiveHistory,
            uploadFullText = { text -> uploadFullText(context, config, text) },
        )
    }

    /** 長い本文の SMS に全文添付を付ける（§4.3）。挙動は通知版と同じ。 */
    suspend fun withFullTextAttachment(
        context: Context,
        payload: SmsPayload,
        fullText: String,
        config: PerantaConfig,
    ): SmsPayload = augmentWithFullText(payload) {
        attachFullTextIfNeeded(
            payload = payload,
            fullText = fullText,
            attachFullTextWhenTruncated = config.attachFullTextWhenTruncated,
            persistSensitiveHistory = config.persistSensitiveHistory,
            uploadFullText = { text -> uploadFullText(context, config, text) },
        )
    }

    /** 全文添付の付与を試み、失敗したら元の [payload]（切り詰めプレビューのみ）へフォールバックする。 */
    private suspend fun <T : Payload> augmentWithFullText(payload: T, block: suspend () -> T): T =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log.w(error) { "full text attach failed; sending truncated preview only id=${payload.id}" }
            payload
        }

    /** 切り詰め前の本文全文を暗号化 blob として blobTopic へアップロードし、[AttachmentRef] を返す（§4.3）。 */
    private suspend fun uploadFullText(context: Context, config: PerantaConfig, text: String): AttachmentRef {
        val repo = androidConfigRepository(context)
        val blobTopic = config.blobTopic ?: repo.ensureBlobTopic()
        val cipher = BlobCipher(Base64.decode(config.sharedKeyBase64!!), config.keyId!!)
        val transport = KtorBlobTransport(config, httpClient)
        return uploadFullTextAttachment(transport, cipher, blobTopic, text)
    }

    /**
     * 通知に元の画像と送信者アイコンを添付した改版を組む（§4.3.1）。どちらも無い・トグル OFF・
     * 伏せ字対象のいずれかなら null を返し、画像なしの初回配送のままにする。
     * 片方だけ符号化に失敗した場合はもう片方だけを付ける。
     * アップロード失敗も握って null を返す（画像が付かないだけで本文の配送は既に済んでいる）。
     */
    suspend fun withNotificationImages(
        context: Context,
        payload: NotificationPayload,
        image: Bitmap?,
        senderIcon: Bitmap?,
        config: PerantaConfig,
    ): NotificationPayload? {
        if (image == null && senderIcon == null) return null
        val allowed = shouldAttachNotificationImage(
            payload = payload,
            attachNotificationImages = config.attachNotificationImages,
            persistSensitiveHistory = config.persistSensitiveHistory,
        )
        if (!allowed) return null
        return try {
            withImageAttachments(
                payload = payload,
                image = image
                    ?.let { encodeNotificationImage(it) ?: logOversized("image", payload.id) }
                    ?.let { uploadImage(context, config, it, imageFileName(payload), NOTIFICATION_IMAGE_MIME) },
                senderIcon = senderIcon
                    ?.let { encodeSenderIcon(it) ?: logOversized("sender icon", payload.id) }
                    ?.let { uploadImage(context, config, it, senderIconFileName(payload), SENDER_ICON_MIME) },
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log.w(error) { "notification image attach failed id=${payload.id}" }
            null
        }
    }

    /** 符号化後が上限を超えて添付を諦めたことを記録し、null を返す。 */
    private fun logOversized(what: String, payloadId: String): ByteArray? {
        log.d { "notification $what exceeds the size budget; skipping id=$payloadId" }
        return null
    }

    private fun imageFileName(payload: NotificationPayload): String =
        "notification-${payload.postedAtEpochMillis}.jpg"

    private fun senderIconFileName(payload: NotificationPayload): String =
        "sender-icon-${payload.postedAtEpochMillis}.png"

    /**
     * 符号化済みの画像を暗号化 blob としてアップロードし、[AttachmentRef] を返す（§4.3.1）。
     * 同一内容を既に上げていれば、その参照を使い回してアップロードを省く。
     * 送信者アイコンは同じ相手からの通知で繰り返し現れるため、この使い回しがよく効く。
     */
    private suspend fun uploadImage(
        context: Context,
        config: PerantaConfig,
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
    ): AttachmentRef {
        val contentHash = contentHashOf(bytes)
        uploadedImages.find(contentHash, nowEpochMillis())?.let { return it }
        val repo = androidConfigRepository(context)
        val blobTopic = config.blobTopic ?: repo.ensureBlobTopic()
        val cipher = BlobCipher(Base64.decode(config.sharedKeyBase64!!), config.keyId!!)
        val transport = KtorBlobTransport(config, httpClient)
        val ref = uploadAttachment(
            transport = transport,
            blobCipher = cipher,
            blobTopic = blobTopic,
            request = AttachmentUploadRequest(
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = bytes.size.toLong(),
                kind = AttachmentKind.IMAGE,
                openSource = { ByteReadChannel(bytes) },
            ),
        )
        uploadedImages.remember(contentHash, ref)
        return ref
    }

    /**
     * composer・テキスト共有からのメッセージ送信（§4.2）。設定不足・失敗は false（例外は漏らさない）。
     */
    suspend fun sendMessage(context: Context, text: String): Boolean {
        val repo = androidConfigRepository(context.applicationContext)
        val config = repo.load().copy(deviceId = repo.ensureDeviceId())
        if (!config.isReadyForSend) return false
        return try {
            val cipher = perantaCipher(config)
            val ntfy = KtorNtfyClient(config, httpClient)
            to.sava.peranta.send.sendMessage(config, cipher, ntfy, SendPipeline(cipher = cipher, ntfy = ntfy, store = timelineFeed), text)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log.w(error) { "message send setup failed" }
            false
        }
    }

    /**
     * 元通知が消えたときの既読同期（§3.4）として、dismiss を全端末へブロードキャストする。
     * [config] は deviceId を確定した状態で渡すこと（コマンドの from に使う）。
     * 送信できた topic があれば true。設定不足・失敗時は false を返し例外を漏らさない。
     */
    suspend fun sendDismissBroadcast(notificationKey: String, config: PerantaConfig): Boolean {
        if (!config.isReadyForSend) {
            log.w { "send not configured; cannot broadcast dismiss" }
            return false
        }
        return try {
            val cipher = perantaCipher(config)
            val ntfy = KtorNtfyClient(config, httpClient)
            val sender = CommandSender(config, cipher, ntfy, SendPipeline(cipher = cipher, ntfy = ntfy, store = timelineFeed))
            sender.dismiss(notificationKey)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log.w(error) { "failed to broadcast dismiss" }
            false
        }
    }
}
