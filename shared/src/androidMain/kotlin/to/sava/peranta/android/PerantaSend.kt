package to.sava.peranta.android

import android.content.Context
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import to.sava.peranta.blob.BlobCipher
import to.sava.peranta.blob.KtorBlobTransport
import to.sava.peranta.blob.uploadFullTextAttachment
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.crypto.MessageCipher
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
import to.sava.peranta.send.attachFullTextIfNeeded
import to.sava.peranta.send.resolveSendTopics
import to.sava.peranta.send.SmsDedupeTracker
import to.sava.peranta.send.NotificationUpdateTracker
import to.sava.peranta.timeline.JsonlTimelineStore
import to.sava.peranta.timeline.TimelineStore
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

    /** 同一通知の連続更新の抑止（§3.1）。 */
    val updates = NotificationUpdateTracker()

    /** 自端末が転送した通知の key を覚え、元通知の削除検知で既読同期の要否を判定する（§3.4）。 */
    val forwarded = ForwardedKeyTracker()

    private val httpClient by lazy { createNtfyHttpClient() }

    /** アプリ専用領域の JSONL タイムラインストア（プロセス内で共有）。 */
    val timelineStore: TimelineStore by lazy { JsonlTimelineStore(defaultTimelineFile()) }

    /**
     * ログの最小重大度を設定する（§16）。
     * [debuggable] が false（リリース）なら Info 以上のみ出力し、topic 名等の debug ログを抑止する。
     */
    fun configureLogging(debuggable: Boolean) {
        Logger.setMinSeverity(if (debuggable) Severity.Debug else Severity.Info)
    }

    /** 起動時にタイムラインを剪定する。失敗しても起動を妨げない。 */
    fun pruneTimelineInBackground() {
        scope.launch {
            try {
                timelineStore.prune(now = nowEpochMillis())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                log.w(error) { "timeline prune failed" }
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
            val pipeline = SendPipeline(cipher = cipher, ntfy = ntfy, store = timelineStore)
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
            val sender = CommandSender(config, cipher, ntfy, SendPipeline(cipher = cipher, ntfy = ntfy, store = timelineStore))
            sender.dismiss(notificationKey)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log.w(error) { "failed to broadcast dismiss" }
            false
        }
    }
}
