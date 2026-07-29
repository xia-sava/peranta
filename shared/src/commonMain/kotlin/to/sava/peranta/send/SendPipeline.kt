package to.sava.peranta.send

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.filter.payloadForPersistence
import to.sava.peranta.model.CommandPayload
import to.sava.peranta.model.Envelope
import to.sava.peranta.model.FilePayload
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.Priority
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.model.encodeEnvelope
import to.sava.peranta.model.newPayloadId
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.net.NtfyClient
import to.sava.peranta.net.NtfyPublishException
import to.sava.peranta.platform.topicForLog
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ErrorKind
import to.sava.peranta.timeline.SentNotification
import to.sava.peranta.timeline.TimelineStore

/** high 優先（OTP 等）の配送で使う短キャッシュ秒数（§8）。 */
const val HIGH_PRIORITY_CACHE_SECONDS: Int = 60

/** 封緘や送信で回復不能な失敗が起きたときにタイムラインへ出す文言。 */
const val SEND_FAILED_MESSAGE: String = "通知の送信に失敗しました"

/** publish がクライアントエラー（4xx）で拒否されたときにタイムラインへ出す文言。 */
const val SEND_REJECTED_MESSAGE: String = "送信が拒否されました。設定を確認してください"

/** 再送を上限まで試して諦めたときにタイムラインへ出す文言。 */
const val SEND_RETRY_GAVE_UP_MESSAGE: String = "再送を諦めました。ネットワークを確認してください"

/** HTTP のクライアントエラー範囲。リトライしても回復しないため即諦める。 */
private val HTTP_CLIENT_ERROR_RANGE: IntRange = 400..499

/** 即時送信の結果分類。 */
private enum class PublishOutcome {
    /** 全 topic へ届いた。 */
    DELIVERED,

    /** リトライで回復し得る失敗（5xx・ネットワーク・タイムアウト）。 */
    RETRY,

    /** リトライしても回復しない失敗（4xx）。 */
    REJECTED,
}

/**
 * Payload を封緘して配送先 topic 群へ publish し、成功時にタイムラインへ送信済みとして残す（§11）。
 * 個々の段階を公開し、Android 層が即時送信と WorkManager 再送で封筒を使い回せるようにする。
 * リトライ制御は呼び出し側（Android 層）の責務とする。
 */
class SendPipeline(
    private val cipher: MessageCipher,
    private val ntfy: NtfyClient,
    private val store: TimelineStore,
    private val log: Logger = Logger.withTag("Send"),
    private val now: () -> Long = ::nowEpochMillis,
) {

    /** [payload] を封緘した Envelope を返す（WorkManager 入力に使い回せる）。 */
    suspend fun seal(payload: Payload): Envelope = cipher.seal(payload)

    /** [payload] の優先度に応じた publish キャッシュ秒数。high なら短キャッシュ、他は既定（null）。 */
    fun cacheSecondsFor(payload: Payload): Int? =
        if (priorityOf(payload) == Priority.HIGH) HIGH_PRIORITY_CACHE_SECONDS else null

    /**
     * 封緘済み本文 [body] を [topics] 全てへ publish する。
     * いずれかの publish が失敗すると例外を送出する（再送は呼び出し側の責務）。
     */
    suspend fun publishEnvelope(body: String, topics: List<String>, cacheSeconds: Int?) {
        topics.forEach { topic ->
            ntfy.publish(topic, body, cacheSeconds)
            log.d { "published to ${topicForLog(topic)} (cache=${cacheSeconds ?: "default"})" }
        }
    }

    /**
     * [payload] を封緘して [topics] へ即時送信する。例外は外へ漏らさず（CancellationException のみ再送出）、
     * 結果を戻り値で表す。
     * - 送信成功: 送信済みを記録して true。
     * - リトライ可能な失敗・[publishTimeoutMillis] 超過: [enqueueRetry] へ封筒と表示メタを渡して false。
     * - リトライ不能な失敗（4xx）・封緘失敗: ErrorItem を記録して false。
     * - [topics] が空: 配送先が解決できていないため送信済みとはみなさず、リトライ可能な失敗と同様に扱う。
     *
     * [persistSensitive] が false のとき、OTP・SMS の本文は履歴に伏せて保存する（§11）。
     * [enqueueRetry] へ渡す表示メタは伏せ字適用後の値で、本文を含まない（再送成功時の記録に使う）。
     */
    suspend fun dispatch(
        payload: Payload,
        topics: List<String>,
        persistSensitive: Boolean,
        publishTimeoutMillis: Long? = null,
        enqueueRetry: suspend (body: String, topics: List<String>, cacheSeconds: Int?, meta: RetryDisplayMeta?) -> Unit,
    ): Boolean {
        return try {
            dispatchOrThrow(payload, topics, persistSensitive, publishTimeoutMillis, enqueueRetry)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log.w(error) { "dispatch failed for id=${payload.id}" }
            runCatching { recordError(SEND_FAILED_MESSAGE) }
                .onFailure { log.w(it) { "failed to record send error item" } }
            false
        }
    }

    private suspend fun dispatchOrThrow(
        payload: Payload,
        topics: List<String>,
        persistSensitive: Boolean,
        publishTimeoutMillis: Long?,
        enqueueRetry: suspend (body: String, topics: List<String>, cacheSeconds: Int?, meta: RetryDisplayMeta?) -> Unit,
    ): Boolean {
        val body = encodeEnvelope(seal(payload))
        val cacheSeconds = cacheSecondsFor(payload)
        return when (publishImmediate(payload, body, topics, cacheSeconds, publishTimeoutMillis)) {
            PublishOutcome.DELIVERED -> {
                recordSent(payload, persistSensitive)
                true
            }

            PublishOutcome.RETRY -> {
                val meta = retryDisplayMetaOf(payloadForPersistence(payload, persistSensitive))
                enqueueRetry(body, topics, cacheSeconds, meta)
                false
            }

            PublishOutcome.REJECTED -> {
                recordError(SEND_REJECTED_MESSAGE)
                false
            }
        }
    }

    private suspend fun publishImmediate(
        payload: Payload,
        body: String,
        topics: List<String>,
        cacheSeconds: Int?,
        publishTimeoutMillis: Long?,
    ): PublishOutcome {
        if (topics.isEmpty()) {
            log.w { "no delivery topics resolved for id=${payload.id}; enqueuing retry" }
            return PublishOutcome.RETRY
        }
        return try {
            val completed = if (publishTimeoutMillis == null) {
                publishEnvelope(body, topics, cacheSeconds)
                true
            } else {
                withTimeoutOrNull(publishTimeoutMillis) {
                    publishEnvelope(body, topics, cacheSeconds)
                } != null
            }
            if (completed) {
                PublishOutcome.DELIVERED
            } else {
                log.w { "immediate publish timed out for id=${payload.id}; enqueuing retry" }
                PublishOutcome.RETRY
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            if (isRetriablePublishError(error)) {
                log.w(error) { "immediate publish failed for id=${payload.id}; enqueuing retry" }
                PublishOutcome.RETRY
            } else {
                log.w(error) { "immediate publish rejected for id=${payload.id}; giving up" }
                PublishOutcome.REJECTED
            }
        }
    }

    /** [payload] を送信済みとしてタイムラインへ追記する。[persistSensitive] が false なら本文を伏せる。 */
    suspend fun recordSent(payload: Payload, persistSensitive: Boolean = true) {
        store.append(
            SentNotification(
                id = payload.id,
                timestampEpochMillis = now(),
                payload = payloadForPersistence(payload, persistSensitive),
                expiresAtEpochMillis = expiresOf(payload),
            ),
        )
        log.i { "sent notification recorded id=${payload.id}" }
    }

    /** [message] をエラーとしてタイムラインへ追記する。 */
    suspend fun recordError(message: String, kind: ErrorKind = ErrorKind.OTHER) {
        store.append(
            ErrorItem(
                id = newPayloadId(),
                timestampEpochMillis = now(),
                message = message,
                kind = kind,
            ),
        )
        log.w { "send error recorded: $message" }
    }

    /**
     * [payload] を封緘し [topics] 全てへ publish して、成功時に送信済みを記録する。
     * publish が失敗した場合は記録せず例外を送出する。
     */
    suspend fun send(payload: Payload, topics: List<String>) {
        val body = encodeEnvelope(seal(payload))
        publishEnvelope(body, topics, cacheSecondsFor(payload))
        recordSent(payload)
    }
}

/** payload 種別の優先度を取り出す。優先度を持たない種別は NORMAL とみなす。 */
fun priorityOf(payload: Payload): Priority = when (payload) {
    is NotificationPayload -> payload.priority
    is SmsPayload -> payload.priority
    is FilePayload -> payload.priority
    else -> Priority.NORMAL
}

/** payload の失効時刻を取り出す。持たない種別は null。 */
fun expiresOf(payload: Payload): Long? = when (payload) {
    is NotificationPayload -> payload.expiresAtEpochMillis
    is SmsPayload -> payload.expiresAtEpochMillis
    is FilePayload -> payload.expiresAtEpochMillis
    is CommandPayload -> payload.expiresAtEpochMillis
    else -> null
}

/** publish の失敗がリトライで回復し得るか。4xx はクライアント側の恒久エラーとみなし諦める。 */
fun isRetriablePublishError(error: Throwable): Boolean = when (error) {
    is NtfyPublishException -> error.status !in HTTP_CLIENT_ERROR_RANGE
    else -> true
}
