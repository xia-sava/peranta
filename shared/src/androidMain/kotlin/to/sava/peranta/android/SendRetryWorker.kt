package to.sava.peranta.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import to.sava.peranta.model.newPayloadId
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.net.KtorNtfyClient
import to.sava.peranta.net.NtfyPublishException
import to.sava.peranta.net.createNtfyHttpClient
import to.sava.peranta.send.RetryDisplayMeta
import to.sava.peranta.send.SEND_REJECTED_MESSAGE
import to.sava.peranta.send.SEND_RETRY_GAVE_UP_MESSAGE
import to.sava.peranta.send.decodeRetryDisplayMeta
import to.sava.peranta.send.encodeRetryDisplayMeta
import to.sava.peranta.send.isRetriablePublishError
import to.sava.peranta.send.toSentNotification
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ErrorKind
import java.util.concurrent.TimeUnit

/**
 * 即時送信に失敗した封筒を再送する WorkManager ジョブ（§3.1）。
 * ワイヤへ出す入力は暗号化済み Envelope JSON のみとし、平文を WorkManager DB に残さない。
 * 表示メタ（本文を含まない）は端末内の WorkManager DB に閉じ、再送成功時のタイムライン記録に使う。
 * cipher / パイプラインには依存せず、ntfy クライアントとタイムラインストアだけで構成する。
 */
class SendRetryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val log = Logger.withTag("SendRetryWorker")

    override suspend fun doWork(): Result {
        val body = inputData.getString(KEY_BODY) ?: run {
            log.w { "retry input has no body; giving up" }
            return Result.failure()
        }
        val topics = inputData.getStringArray(KEY_TOPICS)?.toList().orEmpty()
        if (topics.isEmpty()) {
            log.w { "retry input has no topics; giving up" }
            return Result.failure()
        }
        val cacheSeconds = inputData.getInt(KEY_CACHE_SECONDS, NO_CACHE).takeIf { it != NO_CACHE }

        if (runAttemptCount >= MAX_RETRY_ATTEMPTS) {
            log.w { "retry attempts exhausted ($runAttemptCount); giving up" }
            recordError(SEND_RETRY_GAVE_UP_MESSAGE)
            return Result.failure()
        }

        val config = androidConfigRepository(applicationContext).load()
        val httpClient = createNtfyHttpClient()
        val ntfy = KtorNtfyClient(config, httpClient)
        return try {
            topics.forEach { topic -> ntfy.publish(topic, body, cacheSeconds) }
            log.i { "retry publish succeeded (${topics.size} topics)" }
            recordSentFromMeta(config.deviceName)
            Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: NtfyPublishException) {
            if (isRetriablePublishError(error)) {
                log.w(error) { "retry publish failed (attempt ${runAttemptCount + 1}); backing off" }
                Result.retry()
            } else {
                log.w(error) { "retry publish rejected (${error.status}); giving up" }
                recordError(SEND_REJECTED_MESSAGE)
                Result.failure()
            }
        } catch (error: Exception) {
            log.w(error) { "retry publish failed (attempt ${runAttemptCount + 1}); backing off" }
            Result.retry()
        } finally {
            httpClient.close()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(NOTIFICATION_TITLE)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    /**
     * 表示メタがあれば、再送成功をタイムラインへ送信済みとして残す。
     * メタは本文を持たないため、記録される送信済み項目の本文は空になる。
     */
    private suspend fun recordSentFromMeta(deviceName: String?) {
        val metaJson = inputData.getString(KEY_META) ?: return
        runCatching {
            val meta = decodeRetryDisplayMeta(metaJson)
            PerantaSend.timelineStore.append(
                meta.toSentNotification(from = deviceName.orEmpty(), timestamp = nowEpochMillis()),
            )
        }.onFailure { log.w(it) { "failed to record retried sent notification" } }
    }

    /** [message] をタイムラインへ ErrorItem として残す。 */
    private suspend fun recordError(message: String) {
        runCatching {
            PerantaSend.timelineStore.append(
                ErrorItem(
                    id = newPayloadId(),
                    timestampEpochMillis = nowEpochMillis(),
                    message = message,
                    kind = ErrorKind.OTHER,
                ),
            )
        }.onFailure { log.w(it) { "failed to record error item" } }
    }

    companion object {
        private const val KEY_BODY = "body"
        private const val KEY_TOPICS = "topics"
        private const val KEY_CACHE_SECONDS = "cacheSeconds"
        private const val KEY_META = "displayMeta"
        private const val NO_CACHE = -1

        /** これ以上の runAttemptCount に達したら poison とみなして諦める。 */
        private const val MAX_RETRY_ATTEMPTS = 5

        private const val CHANNEL_ID = "peranta-send-retry"
        private const val CHANNEL_NAME = "送信再試行"
        private const val NOTIFICATION_TITLE = "通知の再送中"
        private const val NOTIFICATION_ID = 4201

        /**
         * 封筒 [body] の再送ジョブを expedited で投入する（ネットワーク接続を制約に指数バックオフ）。
         * [meta] は再送成功時のタイムライン記録に使う表示メタで、publish はせず入力 Data にのみ載せる。
         */
        fun enqueue(
            context: Context,
            body: String,
            topics: List<String>,
            cacheSeconds: Int?,
            meta: RetryDisplayMeta?,
        ) {
            val builder = Data.Builder()
                .putString(KEY_BODY, body)
                .putStringArray(KEY_TOPICS, topics.toTypedArray())
                .putInt(KEY_CACHE_SECONDS, cacheSeconds ?: NO_CACHE)
            meta?.let { builder.putString(KEY_META, encodeRetryDisplayMeta(it)) }
            val request = OneTimeWorkRequestBuilder<SendRetryWorker>()
                .setInputData(builder.build())
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
