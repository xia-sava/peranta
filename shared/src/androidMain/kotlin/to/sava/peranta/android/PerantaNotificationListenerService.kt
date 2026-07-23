package to.sava.peranta.android

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
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
import to.sava.peranta.model.NotificationActionDetail
import to.sava.peranta.model.Priority
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.receive.CommandExecutionException
import to.sava.peranta.send.NotificationInput
import to.sava.peranta.send.prepareForwardedNotification

/**
 * 通知を捕捉して送信パイプラインへ渡す NotificationListenerService（§3.1、§5）。
 * 送信ロールが有効で送信設定が揃っているときだけ処理する。
 * 併せて、逆方向コマンド（§3.4）の実行窓口として自身の生存インスタンスを companion に公開し、
 * 対象通知の取り下げ・アクション発火・インライン返信を [NotificationListenerService] の API で行う。
 */
class PerantaNotificationListenerService : NotificationListenerService() {

    private val log = Logger.withTag("NLS")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        log.i { "notification listener connected" }
        requestPresenceReannounce(applicationContext)
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        requestPresenceReannounce(applicationContext)
        log.i { "notification listener disconnected" }
        super.onListenerDisconnected()
    }

    /**
     * 対象通知を取り下げる（§3.4）。既に存在しない場合は、既に消えているとみなしてログのみとし
     * 致命的エラーにはしない（他端末による削除・ユーザー操作と競合し得るため）。
     */
    fun dismissByKey(key: String) = runNlsAction {
        if (findActiveNotification(key) == null) {
            log.i { "dismiss target not present (already dismissed?) key=$key" }
            return@runNlsAction
        }
        cancelNotification(key)
        log.i { "notification dismissed key=$key" }
    }

    /** 対象通知の [actionIndex] 番のアクションボタンを発火する（§3.4）。 */
    fun invokeActionByKey(key: String, actionIndex: Int) = runNlsAction {
        val action = actionAt(requireActiveNotification(key, NOTIFICATION_ACTION_FAILED_MESSAGE), actionIndex)
        try {
            action.actionIntent.send()
        } catch (e: PendingIntent.CanceledException) {
            log.w(e) { "action send failed key=$key index=$actionIndex" }
            throw CommandExecutionException(NOTIFICATION_ACTION_FAILED_MESSAGE, e)
        }
        log.i { "action invoked key=$key index=$actionIndex" }
    }

    /** 対象アクションの [RemoteInput] に [text] を詰めて発火し、インライン返信する（§3.4）。 */
    fun replyByKey(key: String, actionIndex: Int, text: String) = runNlsAction {
        val action = actionAt(requireActiveNotification(key, NOTIFICATION_REPLY_FAILED_MESSAGE), actionIndex)
        val remoteInputs = action.remoteInputs
        if (remoteInputs.isNullOrEmpty()) {
            log.w { "no remote input for reply key=$key index=$actionIndex" }
            throw CommandExecutionException(NOTIFICATION_REPLY_UNSUPPORTED_MESSAGE)
        }
        val intent = Intent()
        val results = Bundle()
        remoteInputs.forEach { results.putCharSequence(it.resultKey, text) }
        RemoteInput.addResultsToIntent(remoteInputs, intent, results)
        try {
            action.actionIntent.send(applicationContext, 0, intent)
        } catch (e: PendingIntent.CanceledException) {
            log.w(e) { "reply send failed key=$key index=$actionIndex" }
            throw CommandExecutionException(NOTIFICATION_REPLY_FAILED_MESSAGE, e)
        }
        log.i { "reply sent key=$key index=$actionIndex" }
    }

    /**
     * NLS の実行系 API 呼び出しを包み、切断レース等で投げられる [SecurityException] 等の
     * システム例外も [CommandExecutionException] へ写して呼び出し元のエラー処理に一貫させる。
     */
    private inline fun runNlsAction(block: () -> Unit) {
        try {
            block()
        } catch (e: CommandExecutionException) {
            throw e
        } catch (e: SecurityException) {
            throw CommandExecutionException("通知リスナーが切断されました", e)
        }
    }

    private fun findActiveNotification(key: String): StatusBarNotification? =
        activeNotifications?.firstOrNull { it.key == key }

    private fun requireActiveNotification(key: String, notFoundMessage: String): StatusBarNotification {
        val sbn = findActiveNotification(key)
        if (sbn == null) {
            log.w { "target notification not found key=$key" }
            throw CommandExecutionException(notFoundMessage)
        }
        return sbn
    }

    private fun actionAt(sbn: StatusBarNotification, index: Int): Notification.Action {
        val actions = sbn.notification.actions
        if (actions == null || index !in actions.indices) {
            log.w { "action index out of range key=${sbn.key} index=$index" }
            throw CommandExecutionException(NOTIFICATION_ACTION_INDEX_MISSING_MESSAGE)
        }
        return actions[index]
    }

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
        if (config.deviceName == null) {
            log.w { "device name missing; skipping notification" }
            return
        }
        val deviceId = androidConfigRepository().ensureDeviceId()

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
        if (PerantaSend.reposts.isUnchangedRepost(sbn.key, fields.title.orEmpty(), fields.text.orEmpty())) {
            log.d { "skipping unchanged repost from $packageName" }
            return
        }

        val input = NotificationInput(
            packageName = packageName,
            appName = resolveAppName(packageName),
            title = fields.title.orEmpty(),
            text = fields.text.orEmpty(),
            notificationKey = sbn.key,
            actions = fields.actions,
            actionDetails = fields.actionDetails,
            postedAtEpochMillis = sbn.postTime,
            priority = resolvePriority(sbn.notification),
        )
        val isCrossProfile = sbn.user != Process.myUserHandle()
        forward(input, deviceId, config, isCrossProfile)
    }

    private fun forward(
        input: NotificationInput,
        deviceId: String,
        config: PerantaConfig,
        isCrossProfilePackage: Boolean,
    ) {
        val prepared = prepareForwardedNotification(
            input = input,
            mode = config.filterMode,
            rules = config.filterRules,
            deviceId = deviceId,
            now = nowEpochMillis(),
            otpSenderPackages = config.otpSenderPackages,
            isImplicitlySystemPackage = packageManagerSystemPackagePredicate(
                applicationContext.packageManager,
                isCrossProfilePackage = isCrossProfilePackage,
            ),
            deviceName = config.deviceName,
        ) ?: run {
            log.d { "filtered out notification from ${input.packageName}" }
            return
        }
        val sendConfig = config.copy(deviceId = deviceId)
        // 転送対象にした通知の key を覚え、元通知が消えたときの既読同期（§3.4）で参照する。
        PerantaSend.forwarded.remember(prepared.payload.notificationKey)
        // 内容も記録し、同一内容のままの再投稿（未読会話の再掲など）を次回以降スキップする（§3.1）。
        PerantaSend.reposts.recordForwarded(input.notificationKey, input.title, input.text)
        scope.launch {
            // 長文本文なら全文を暗号化 blob として添付し、インラインは切り詰めプレビューにする（§4.3）。
            val payload = PerantaSend.withFullTextAttachment(
                applicationContext, prepared.payload, prepared.fullText, sendConfig,
            )
            if (PerantaSend.dispatch(applicationContext, payload, sendConfig)) {
                log.i { "notification sent id=${payload.id}" }
            } else {
                log.d { "notification queued for retry or dropped id=${payload.id}" }
            }
        }
    }

    /**
     * 元通知が消えたら既読同期の dismiss を全受信端末へブロードキャストする（§3.4）。
     * 自端末が転送対象にした通知（[PerantaSend.forwarded] に記録済み）に限定し、
     * 他アプリの無関係な通知削除まで拾わない。
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        try {
            handleRemoved(sbn)
        } catch (error: Exception) {
            log.w(error) { "onNotificationRemoved failed" }
        }
    }

    private fun handleRemoved(sbn: StatusBarNotification) {
        val config = androidConfigRepository().load()
        if (!config.sendEnabled || !config.isReadyForSend) return
        PerantaSend.reposts.forget(sbn.key)
        if (!PerantaSend.forwarded.consume(sbn.key)) {
            log.d { "removed notification was not forwarded; ignoring key=${sbn.key}" }
            return
        }
        val sendConfig = config.copy(deviceId = androidConfigRepository().ensureDeviceId())
        scope.launch {
            if (PerantaSend.sendDismissBroadcast(sbn.key, sendConfig)) {
                log.i { "dismiss broadcast for removed notification key=${sbn.key}" }
            } else {
                log.d { "dismiss broadcast not sent for key=${sbn.key}" }
            }
        }
    }

    private fun extractFields(sbn: StatusBarNotification): NotificationFields {
        val extras: Bundle = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        // title が無いアクションは従来どおり除外し、actionDetails も同じ走査で組んで index を対応させる（§5）。
        val namedActions = sbn.notification.actions
            ?.mapNotNull { action -> action.title?.toString()?.let { action to it } }
            .orEmpty()
        val actions = namedActions.map { it.second }
        val actionDetails = namedActions.map { (action, _) ->
            actionDetailOf(
                rawSemanticAction = action.semanticAction,
                hasRemoteInput = !action.remoteInputs.isNullOrEmpty(),
                isActivity = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    action.actionIntent?.isActivity
                } else {
                    null
                },
            )
        }
        return NotificationFields(title = title, text = text, actions = actions, actionDetails = actionDetails)
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
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    private data class NotificationFields(
        val title: String?,
        val text: String?,
        val actions: List<String>,
        val actionDetails: List<NotificationActionDetail> = emptyList(),
    )

    companion object {
        @Volatile
        private var instance: PerantaNotificationListenerService? = null

        /**
         * 現在 OS に bind・接続されているサービスインスタンス。逆方向コマンドの実行窓口（§3.4）。
         * 未接続（権限未付与・OS 未 bind）なら null。
         */
        val activeInstance: PerantaNotificationListenerService?
            get() = instance
    }
}
