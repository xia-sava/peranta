package to.sava.peranta.android

import android.app.Notification
import android.os.Bundle
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.model.Priority
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.send.NotificationInput
import to.sava.peranta.send.buildNotificationPayload

/**
 * 通知を捕捉して送信パイプラインへ渡す NotificationListenerService（§3.1、§5）。
 * 送信ロールが有効で送信設定が揃っているときだけ処理する。
 */
class PerantaNotificationListenerService : NotificationListenerService() {

    private val log = Logger.withTag("NLS")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handlePosted(sbn)
        } catch (error: Exception) {
            log.w(error) { "onNotificationPosted failed" }
        }
    }

    private fun handlePosted(sbn: StatusBarNotification) {
        val config = androidConfigRepository().load()
        if (!config.sendEnabled) return
        if (!config.isReadyForSend) {
            log.w { "send enabled but not configured; skipping notification" }
            return
        }
        val deviceName = config.deviceName ?: run {
            log.w { "device name missing; skipping notification" }
            return
        }

        val packageName = sbn.packageName
        val fields = extractFields(sbn)
        val defaultSmsPackage = runCatching {
            Telephony.Sms.getDefaultSmsPackage(applicationContext)
        }.getOrNull()
        val now = nowEpochMillis()
        val smsDuplicate = PerantaSend.dedupe.isDuplicateNotification(
            title = fields.title,
            text = fields.text,
            at = now,
        )
        if (shouldSkipNotification(
                packageName = packageName,
                defaultSmsPackage = defaultSmsPackage,
                isSmsDuplicate = smsDuplicate,
                isOngoing = sbn.isOngoing,
                isGroupSummary = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            )
        ) {
            log.d { "skipping notification from $packageName" }
            return
        }
        if (PerantaSend.updates.isRepeatUpdate(sbn.key, fields.text.orEmpty(), now)) {
            log.d { "skipping repeated update from $packageName" }
            return
        }

        val input = NotificationInput(
            packageName = packageName,
            appName = resolveAppName(packageName),
            title = fields.title.orEmpty(),
            text = fields.text.orEmpty(),
            notificationKey = sbn.key,
            actions = fields.actions,
            postedAtEpochMillis = sbn.postTime,
            priority = resolvePriority(sbn.notification),
        )
        forward(input, deviceName, config)
    }

    private fun forward(input: NotificationInput, deviceName: String, config: PerantaConfig) {
        val payload = buildNotificationPayload(
            input = input,
            mode = config.filterMode,
            rules = config.filterRules,
            deviceName = deviceName,
            now = nowEpochMillis(),
            otpSenderPackages = config.otpSenderPackages,
        ) ?: run {
            log.d { "filtered out notification from ${input.packageName}" }
            return
        }
        scope.launch {
            if (PerantaSend.dispatch(applicationContext, payload, config)) {
                log.i { "notification sent id=${payload.id}" }
            } else {
                log.d { "notification queued for retry or dropped id=${payload.id}" }
            }
        }
    }

    private fun extractFields(sbn: StatusBarNotification): NotificationFields {
        val extras: Bundle = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        val actions = sbn.notification.actions
            ?.mapNotNull { it.title?.toString() }
            .orEmpty()
        return NotificationFields(title = title, text = text, actions = actions)
    }

    /** 元通知の priority（PRIORITY_MIN..PRIORITY_MAX）を転送用の [Priority] に写す。 */
    @Suppress("DEPRECATION")
    private fun resolvePriority(notification: Notification): Priority = when {
        notification.priority >= Notification.PRIORITY_HIGH -> Priority.HIGH
        notification.priority <= Notification.PRIORITY_LOW -> Priority.LOW
        else -> Priority.NORMAL
    }

    /** パッケージ名からアプリ表示名を解決する。取得できなければパッケージ名を返す。 */
    private fun resolveAppName(packageName: String): String = runCatching {
        val pm = applicationContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private data class NotificationFields(
        val title: String?,
        val text: String?,
        val actions: List<String>,
    )
}
