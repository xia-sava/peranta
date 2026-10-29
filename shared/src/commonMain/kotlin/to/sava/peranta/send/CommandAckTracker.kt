package to.sava.peranta.send

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import to.sava.peranta.model.CommandAckPayload
import to.sava.peranta.model.CommandOutcome
import to.sava.peranta.model.CommandType
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.roster.CAPABILITY_COMMAND_ACK
import to.sava.peranta.roster.RosterFetchResult
import to.sava.peranta.roster.topicOf
import to.sava.peranta.timeline.ErrorKind

/**
 * 応答（§3.4）を待つ時間。実行端末はロスターを引かずに [AckRequest.ackTopic] へ直接応答するため、
 * 平常の往復は 1 秒前後に収まる。
 */
const val COMMAND_ACK_TIMEOUT_MILLIS: Long = 5_000L

/** 同じ端末について「応答がない」を知らせる最小間隔。続けて操作したときに警告が積み上がらないよう絞る。 */
const val UNACKNOWLEDGED_WARNING_INTERVAL_MILLIS: Long = 10 * 60 * 1000L

/**
 * コマンドに添える応答の求め（§3.4）。[ackBy] が応答すべき端末、[ackTopic] が自端末の受信 topic、
 * [targetName] は警告の文言に出す応答側の端末名。
 */
data class AckRequest(
    val ackBy: String,
    val ackTopic: String,
    val targetName: String,
)

/**
 * ロスターから [ackBy] へ応答を求められるかを判定し、求めの中身を組む（§3.4）。
 * 次のどれかに当たるときは求めない（null）。応答を返せない端末を待って誤って警告しないためである。
 * - ロスターの取得に失敗した
 * - [ackBy] がロスターに無い、または応答に対応した版であること（[CAPABILITY_COMMAND_ACK]）を名乗っていない
 * - [ackBy] が自端末
 * - 自端末の受信 topic がロスターから引けない（応答の送り先が無い）
 */
fun ackRequestFor(roster: RosterFetchResult, selfDeviceId: String, ackBy: String): AckRequest? {
    if (ackBy == selfDeviceId) return null
    val entries = (roster as? RosterFetchResult.Fetched)?.entries ?: return null
    val target = entries.firstOrNull { it.deviceId == ackBy }
        ?.takeIf { CAPABILITY_COMMAND_ACK in it.capabilities }
        ?: return null
    val selfTopic = entries.firstOrNull { it.deviceId == selfDeviceId }
        ?.let { topicOf(it.endpoint) }
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return AckRequest(ackBy = ackBy, ackTopic = selfTopic, targetName = target.deviceName)
}

/**
 * 読み直しを遡らせる幅。読み直しは publish 時刻以降をサーバの時計で引くため、端末とサーバの時計のずれを
 * 吸収する。応答のサーバ保持（60 秒）を超えて遡っても拾えるものは増えない。
 */
private const val ACK_HISTORY_LOOKBACK_MILLIS: Long = 60_000L

/**
 * 応答が来ず、自端末の受信 topic を読み直しても無かったときの利用者向け文言。
 * 自端末の受け口は読み直しで確かめ済みなので、応答側（いつも通知を転送した Android）の受け口を名指す。
 */
fun unacknowledgedCommandMessage(targetName: String): String =
    "$targetName から応答がありません。操作が届いていない可能性があります。" +
        "$targetName の受信（ntfy アプリの接続）が止まっていないか確認してください。"

/** 応答が来ず、自端末の受信 topic も読み直せなかったときの利用者向け文言。どちらの受け口かを決めつけない。 */
fun unconfirmedCommandMessage(targetName: String): String =
    "$targetName から応答がありません。操作が届いていない可能性があります。" +
        "$targetName かこの端末の受信が止まっていないか確認してください。"

/** 実行端末が失敗を返したときの利用者向け文言。 */
fun failedCommandMessage(targetName: String, reason: String?): String =
    "$targetName での操作に失敗しました。" + (reason ?: "理由は分かりません。")

/**
 * 送ったコマンドへの応答を待ち、届かない・失敗したことを知らせる（§3.4）。
 *
 * コマンドごとに [register] → publish → [arm] と呼び、publish に失敗したら [cancel] で取り消す。
 * 待ち時間は publish を終えてから数える（publish が遅いだけで応答なしと取り違えないため）。
 * 応答が publish より先に届いても取りこぼさないよう、登録は publish の前に行う。
 *
 * 待ち時間を過ぎたら、知らせる前に [history] で自端末の受信 topic を読み直す。自端末の購読が
 * 切れているだけ（スリープ復帰直後など）で応答を取りこぼしたときに、相手の端末を誤って疑わないため。
 *
 * 知らせる先は [onProblem]。受信処理の中から [onAck] が呼ばれても受信側の排他を握り合わないよう、
 * 知らせは必ず [scope] の上で別に走らせる。待ちの状態はメモリにだけ持つ。
 */
class CommandAckTracker(
    private val scope: CoroutineScope,
    private val onProblem: suspend (kind: ErrorKind, message: String) -> Unit,
    private val history: CommandAckHistory? = null,
    private val timeoutMillis: Long = COMMAND_ACK_TIMEOUT_MILLIS,
    private val warningIntervalMillis: Long = UNACKNOWLEDGED_WARNING_INTERVAL_MILLIS,
    private val now: () -> Long = ::nowEpochMillis,
    private val log: Logger = Logger.withTag("CommandAck"),
) {
    private class Pending(val command: CommandType, val request: AckRequest) {
        var timer: Job? = null
        var armedAtEpochMillis: Long = 0
    }

    private val mutex = Mutex()
    private val pending = mutableMapOf<String, Pending>()
    private val lastWarnedAt = mutableMapOf<String, Long>()

    /** [commandId] の応答待ちを登録する。待ち時間はまだ数え始めない。 */
    suspend fun register(commandId: String, command: CommandType, request: AckRequest) {
        mutex.withLock { pending[commandId] = Pending(command, request) }
    }

    /** publish を終えた [commandId] の待ち時間を数え始める。既に応答が届いていれば何もしない。 */
    suspend fun arm(commandId: String) {
        mutex.withLock {
            val entry = pending[commandId] ?: return
            entry.armedAtEpochMillis = now()
            entry.timer = scope.launch {
                delay(timeoutMillis)
                expire(commandId)
            }
        }
    }

    /** publish に失敗した [commandId] の応答待ちを取り消す。 */
    suspend fun cancel(commandId: String) {
        mutex.withLock { pending.remove(commandId) }?.timer?.cancel()
    }

    /** 届いた応答で待ちを解き、失敗を返されたらその理由を知らせる。待っていない応答（遅着など）は記録だけにする。 */
    suspend fun onAck(ack: CommandAckPayload) {
        val entry = mutex.withLock { pending.remove(ack.commandId) } ?: run {
            log.i { "ack for unknown or expired command id=${ack.commandId} outcome=${ack.outcome}" }
            return
        }
        entry.timer?.cancel()
        settle(entry, ack)
    }

    /**
     * 応答を受け取ったコマンドを片づけ、失敗が返っていればその理由を知らせる。
     * 対象通知が既に存在しなかったアクション発火は知らせない。実行端末が同報する dismiss で
     * アイテムは「元通知は消えた」状態になり、操作できなかったことはそれで分かる。
     * 返信は打った本文が届かなかったことを知らせるため、対象通知が無くても知らせる。
     */
    private fun settle(entry: Pending, ack: CommandAckPayload) {
        log.i {
            "command acknowledged id=${ack.commandId} command=${entry.command} outcome=${ack.outcome}" +
                " targetMissing=${ack.targetMissing}"
        }
        if (ack.outcome != CommandOutcome.FAILED) return
        if (ack.targetMissing && entry.command != CommandType.REPLY) return
        report(ErrorKind.COMMAND_EXECUTION, failedCommandMessage(entry.request.targetName, ack.message))
    }

    /**
     * 待ち時間を過ぎたコマンドを片づける。自端末の受信 topic に応答が残っていれば受け取ったものとして扱い、
     * 無ければ応答なしを知らせる。読み直せたかどうかで、どちらの受け口を疑うかの文言を変える。
     */
    private suspend fun expire(commandId: String) {
        val entry = mutex.withLock { pending.remove(commandId) } ?: return
        val reread = history?.invoke(entry.request.ackTopic, entry.armedAtEpochMillis - ACK_HISTORY_LOOKBACK_MILLIS)
        reread?.firstOrNull { it.commandId == commandId }?.let { ack ->
            log.i { "command ack recovered from history id=$commandId" }
            settle(entry, ack)
            return
        }
        log.w { "command not acknowledged id=$commandId command=${entry.command} by=${entry.request.ackBy}" }
        if (!claimWarning(entry.request.ackBy)) return
        val message = if (reread != null) {
            unacknowledgedCommandMessage(entry.request.targetName)
        } else {
            unconfirmedCommandMessage(entry.request.targetName)
        }
        report(ErrorKind.COMMAND_UNACKNOWLEDGED, message)
    }

    /** [deviceId] についての警告を今出してよいか。出すならその時刻を記録する。 */
    private suspend fun claimWarning(deviceId: String): Boolean = mutex.withLock {
        val at = now()
        val last = lastWarnedAt[deviceId]
        if (last != null && at - last < warningIntervalMillis) return@withLock false
        lastWarnedAt[deviceId] = at
        true
    }

    private fun report(kind: ErrorKind, message: String) {
        scope.launch {
            try {
                onProblem(kind, message)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                log.w(error) { "failed to report command ack problem [$kind]" }
            }
        }
    }
}
