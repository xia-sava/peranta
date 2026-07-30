package to.sava.peranta.send

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.CommandType
import to.sava.peranta.model.MAX_REPLY_TEXT_BYTES
import to.sava.peranta.model.encodeEnvelope
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.model.truncateToUtf8Bytes
import to.sava.peranta.net.NtfyClient
import to.sava.peranta.roster.RosterStore
import to.sava.peranta.roster.resolveTargetTopic

/**
 * 受信端末・送信端末の双方から、通知への操作コマンドを配送する（§3.4）。
 * 宛先は command 種別で分類する:
 * - dismiss: 既読同期のため全端末へブロードキャスト（`to: "*"`、自分除外は [resolveSendTopics] に委ねる）。
 * - invokeAction / reply / muteApp: 実行できるのは元通知の送信元（スマホ）だけなので、その deviceId へ一点指定する。
 *
 * 失効までの猶予とサーバのキャッシュ保持は、種別ごとの配送特性（[CommandDelivery]）から引く。
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
    private val now: () -> Long = ::nowEpochMillis,
    private val log: Logger = Logger.withTag("CommandSend"),
) {

    private val selfDeviceId: String =
        config.deviceId ?: error("deviceId not configured for command sending")

    /** 既読同期の dismiss を全端末へブロードキャストする（§3.4）。 */
    suspend fun dismiss(targetNotificationKey: String): Boolean =
        publish(
            command = CommandType.DISMISS,
            to = BROADCAST_TARGET,
            topics = resolveSendTopics(config, cipher, ntfy),
            targetNotificationKey = targetNotificationKey,
        )

    /** [targetDeviceId]（元通知の送信元）へアクション発火コマンドを送る（§3.4）。 */
    suspend fun invokeAction(
        targetDeviceId: String,
        targetNotificationKey: String,
        actionIndex: Int,
    ): Boolean =
        publish(
            command = CommandType.INVOKE_ACTION,
            to = targetDeviceId,
            topics = singleTargetTopics(targetDeviceId),
            targetNotificationKey = targetNotificationKey,
            actionIndex = actionIndex,
        )

    /** [targetDeviceId] へインライン返信コマンドを送る（§3.4）。本文は [MAX_REPLY_TEXT_BYTES] へ切り詰める。 */
    suspend fun reply(
        targetDeviceId: String,
        targetNotificationKey: String,
        actionIndex: Int,
        text: String,
    ): Boolean =
        publish(
            command = CommandType.REPLY,
            to = targetDeviceId,
            topics = singleTargetTopics(targetDeviceId),
            targetNotificationKey = targetNotificationKey,
            actionIndex = actionIndex,
            replyText = truncateToUtf8Bytes(text, MAX_REPLY_TEXT_BYTES),
        )

    /** [targetDeviceId] のスマホへアプリ非表示（denylist 追加）コマンドを送る（§3.4 / §7）。 */
    suspend fun muteApp(targetDeviceId: String, packageName: String): Boolean =
        publish(
            command = CommandType.MUTE_APP,
            to = targetDeviceId,
            topics = singleTargetTopics(targetDeviceId),
            packageName = packageName,
        )

    /** [targetDeviceId] のスマホへアプリ非表示解除（denylist から除去）コマンドを送る（§3.4 / §7）。 */
    suspend fun unmuteApp(targetDeviceId: String, packageName: String): Boolean =
        publish(
            command = CommandType.UNMUTE_APP,
            to = targetDeviceId,
            topics = singleTargetTopics(targetDeviceId),
            packageName = packageName,
        )

    /**
     * 一点指定コマンドの宛先 topic を解決する。ロスター（control topic）から [targetDeviceId] の
     * エンドポイントを引く。control topic 未設定・取得失敗・対象不在なら空を返す。
     */
    private suspend fun singleTargetTopics(targetDeviceId: String): List<String> {
        val controlTopic = config.controlTopic ?: run {
            log.w { "no control topic; cannot resolve target device=$targetDeviceId" }
            return emptyList()
        }
        val result = RosterStore(ntfy, cipher, controlTopic).fetch()
        return resolveTargetTopic(result, targetDeviceId)?.let { listOf(it) } ?: emptyList()
    }

    private suspend fun publish(
        command: CommandType,
        to: String,
        topics: List<String>,
        targetNotificationKey: String? = null,
        actionIndex: Int? = null,
        replyText: String? = null,
        packageName: String? = null,
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
        )
        return try {
            val body = encodeEnvelope(pipeline.seal(payload))
            pipeline.publishEnvelope(body, topics, cacheSeconds = deliveryOf(command).cacheSeconds)
            log.i { "command sent command=$command to=$to topics=${topics.size}" }
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log.w(error) { "failed to send command=$command to=$to" }
            false
        }
    }
}
