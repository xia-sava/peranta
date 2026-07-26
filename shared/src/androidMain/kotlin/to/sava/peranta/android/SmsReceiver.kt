package to.sava.peranta.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.send.buildSmsPayload

/** 即時送信を打ち切る時間枠。超過分は WorkManager 再送へ委ねる（§3.1）。 */
private const val SMS_PUBLISH_TIMEOUT_MILLIS: Long = 8_000L

/**
 * SMS を直接受信して送信パイプラインへ渡す（§3.1）。
 * 送信ロールと「SMS を直接受信する」が有効なときだけ処理する。分割 SMS は送信元ごとに連結する。
 */
class SmsReceiver : BroadcastReceiver() {

    private val log = Logger.withTag("SmsReceiver")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        try {
            handleReceive(context.applicationContext, intent)
        } catch (error: Exception) {
            log.w(error) { "onReceive failed" }
        }
    }

    private fun handleReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val config = androidConfigRepository(context).load()
        if (!config.sendEnabled || !config.smsDirectReceive) return
        if (!config.isReadyForSend) {
            log.w { "send enabled but not configured; skipping sms" }
            return
        }
        if (config.deviceName == null) {
            log.w { "device name missing; skipping sms" }
            return
        }
        val deviceId = androidConfigRepository(context).ensureDeviceId()

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (messages.isEmpty()) return
        val first = messages.first()
        val senderNumber = first.displayOriginatingAddress ?: first.originatingAddress.orEmpty()
        val body = messages.joinToString(separator = "") { it.displayMessageBody.orEmpty() }
        if (body.isBlank()) return

        val now = nowEpochMillis()
        PerantaSend.dedupe.recordSms(body, now)
        val payload = buildSmsPayload(
            senderNumber = senderNumber,
            text = body,
            deviceId = deviceId,
            now = now,
            deviceName = config.deviceName,
        )
        dispatchAsync(context, payload, body, config.copy(deviceId = deviceId))
    }

    private fun dispatchAsync(context: Context, payload: SmsPayload, fullText: String, config: PerantaConfig) {
        val pendingResult = goAsync()
        scope.launch {
            try {
                // 長文 SMS なら全文を暗号化 blob として添付し、インラインは切り詰めプレビューにする（§4.3）。
                val sent = PerantaSend.withFullTextAttachment(context, payload, fullText, config)
                // 転送内容が確定した時点で本文と結びつけ、後から出る SMS アプリの通知と対応づける（§3.1）。
                PerantaSend.dedupe.recordForwarded(fullText, sent)
                if (PerantaSend.dispatch(context, sent, config, publishTimeoutMillis = SMS_PUBLISH_TIMEOUT_MILLIS)) {
                    log.i { "sms sent id=${sent.id}" }
                } else {
                    log.d { "sms queued for retry or dropped id=${sent.id}" }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
