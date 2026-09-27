package to.sava.peranta.send

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.model.AppRuleSettings
import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.CommandType
import to.sava.peranta.model.MAX_REPLY_TEXT_BYTES
import to.sava.peranta.model.encodeEnvelope
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.model.truncateToUtf8Bytes
import to.sava.peranta.net.NtfyClient
import to.sava.peranta.roster.RosterFetchResult
import to.sava.peranta.roster.RosterStore
import to.sava.peranta.roster.resolveTargetTopic

/**
 * 受信端末・送信端末の双方から、通知への操作コマンドを配送する（§3.4）。
 * 宛先は command 種別で分類する:
 * - dismiss: 既読同期のため全端末へブロードキャスト（`to: "*"`、自分除外は [resolveSendTopics] に委ねる）。
 * - invokeAction / reply / muteApp: 実行できるのは元通知の送信元だけなので、その deviceId へ一点指定する。
 *
 * 失効までの猶予とサーバのキャッシュ保持は、種別ごとの配送特性（[CommandDelivery]）から引く。
 *
 * 元通知への操作（アクション発火・返信・元通知の転送元が分かっている dismiss）は、[ackTracker] があれば
 * 実行端末に応答を求め、届かない・失敗したことを知らせる。求めるかどうかはロスターで決める（[ackRequestFor]）。
 *
 * publish は封緘済みでのみ行い、タイムラインへは記録しない（コマンドは履歴ではなく操作のため）。
 * 送信できた topic があれば true を返す。宛先が解決できない・publish に失敗した場合は false を返し、
 * 例外はログに残して外へ漏らさない（[CancellationException] を除く）。
 */
class CommandSender(
    private val config: PerantaConfig,
    private val cipher: MessageCipher,
    private val ntfy: NtfyClient,
    private val pipeline: SendPipeline,
    private val ackTracker: CommandAckTracker? = null,
    private val now: () -> Long = ::nowEpochMillis,
    private val log: Logger = Logger.withTag("CommandSend"),
) {

    private val selfDeviceId: String =
        config.deviceId ?: error("deviceId not configured for command sending")

    /**
     * 既読同期の dismiss を全端末へブロードキャストする（§3.4）。
     * [sourceDeviceId] は元通知を転送した端末で、分かっていればその端末に応答を求める。
     */
    suspend fun dismiss(targetNotificationKey: String, sourceDeviceId: String? = null): Boolean {
        val roster = fetchRoster()
        return publish(
            command = CommandType.DISMISS,
            to = BROADCAST_TARGET,
            topics = resolveSendTopics(config, roster),
            ackRequest = sourceDeviceId?.let { ackRequestOrNull(roster, it) },
            targetNotificationKey = targetNotificationKey,
        )
    }

    /** [targetDeviceId]（元通知の送信元）へアクション発火コマンドを送る（§3.4）。 */
    suspend fun invokeAction(
        targetDeviceId: String,
        targetNotificationKey: String,
        actionIndex: Int,
    ): Boolean {
        val roster = fetchRoster()
        return publish(
            command = CommandType.INVOKE_ACTION,
            to = targetDeviceId,
            topics = singleTargetTopics(roster, targetDeviceId),
            ackRequest = ackRequestOrNull(roster, targetDeviceId),
            targetNotificationKey = targetNotificationKey,
            actionIndex = actionIndex,
        )
    }

    /** [targetDeviceId] へインライン返信コマンドを送る（§3.4）。本文は [MAX_REPLY_TEXT_BYTES] へ切り詰める。 */
    suspend fun reply(
        targetDeviceId: String,
        targetNotificationKey: String,
        actionIndex: Int,
        text: String,
    ): Boolean {
        val roster = fetchRoster()
        return publish(
            command = CommandType.REPLY,
            to = targetDeviceId,
            topics = singleTargetTopics(roster, targetDeviceId),
            ackRequest = ackRequestOrNull(roster, targetDeviceId),
            targetNotificationKey = targetNotificationKey,
            actionIndex = actionIndex,
            replyText = truncateToUtf8Bytes(text, MAX_REPLY_TEXT_BYTES),
        )
    }

    /** [targetDeviceId] の端末へアプリ非表示（denylist 追加）コマンドを送る（§3.4 / §7）。 */
    suspend fun muteApp(targetDeviceId: String, packageName: String): Boolean =
        publish(
            command = CommandType.MUTE_APP,
            to = targetDeviceId,
            topics = singleTargetTopics(fetchRoster(), targetDeviceId),
            packageName = packageName,
        )

    /** [targetDeviceId] の端末へアプリ非表示解除（denylist から除去）コマンドを送る（§3.4 / §7）。 */
    suspend fun unmuteApp(targetDeviceId: String, packageName: String): Boolean =
        publish(
            command = CommandType.UNMUTE_APP,
            to = targetDeviceId,
            topics = singleTargetTopics(fetchRoster(), targetDeviceId),
            packageName = packageName,
        )

    /** [targetDeviceId] の端末へアプリごとの扱いの更新を送る（§3.4 / §7）。 */
    suspend fun setAppRule(targetDeviceId: String, packageName: String, settings: AppRuleSettings): Boolean =
        publish(
            command = CommandType.SET_APP_RULE,
            to = targetDeviceId,
            topics = singleTargetTopics(fetchRoster(), targetDeviceId),
            packageName = packageName,
            appRule = settings,
        )

    /** ロスター（control topic）を 1 回だけ引く。control topic 未設定なら null。 */
    private suspend fun fetchRoster(): RosterFetchResult? {
        val controlTopic = config.controlTopic ?: return null
        return RosterStore(ntfy, cipher, controlTopic).fetch()
    }

    /**
     * 一点指定コマンドの宛先 topic を解決する。ロスターから [targetDeviceId] のエンドポイントを引く。
     * control topic 未設定・取得失敗・対象不在なら空を返す。
     */
    private fun singleTargetTopics(roster: RosterFetchResult?, targetDeviceId: String): List<String> {
        if (roster == null) {
            log.w { "no control topic; cannot resolve target device=$targetDeviceId" }
            return emptyList()
        }
        return resolveTargetTopic(roster, targetDeviceId)?.let { listOf(it) } ?: emptyList()
    }

    /** 応答待ちができるとき（[ackTracker] があり、ロスターが応答を求められると言う）だけ求めを組む。 */
    private fun ackRequestOrNull(roster: RosterFetchResult?, ackBy: String): AckRequest? {
        if (ackTracker == null || roster == null) return null
        return ackRequestFor(roster, selfDeviceId, ackBy)
    }

    private suspend fun publish(
        command: CommandType,
        to: String,
        topics: List<String>,
        ackRequest: AckRequest? = null,
        targetNotificationKey: String? = null,
        actionIndex: Int? = null,
        replyText: String? = null,
        packageName: String? = null,
        appRule: AppRuleSettings? = null,
    ): Boolean {
        if (topics.isEmpty()) {
            log.w { "no delivery topics resolved for command=$command to=$to" }
            return false
        }
        val payload = buildCommandPayload(
            command = command,
            from = selfDeviceId,
            to = to,
            now = now(),
            targetNotificationKey = targetNotificationKey,
            actionIndex = actionIndex,
            replyText = replyText,
            packageName = packageName,
            appRule = appRule,
            ackRequest = ackRequest,
        )
        val tracker = ackTracker?.takeIf { ackRequest != null }
        return try {
            val body = encodeEnvelope(pipeline.seal(payload))
            ackRequest?.let { tracker?.register(payload.id, command, it) }
            pipeline.publishEnvelope(body, topics, cacheSeconds = deliveryOf(command).cacheSeconds)
            tracker?.arm(payload.id)
            log.i { "command sent command=$command to=$to topics=${topics.size} ack=${ackRequest != null}" }
            true
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) { tracker?.cancel(payload.id) }
            throw cancellation
        } catch (error: Exception) {
            tracker?.cancel(payload.id)
            log.w(error) { "failed to send command=$command to=$to" }
            false
        }
    }
}
