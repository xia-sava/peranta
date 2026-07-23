package to.sava.peranta.receive

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerializationException
import to.sava.peranta.crypto.DecryptionException
import to.sava.peranta.crypto.KeyIdMismatchException
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.filter.payloadForPersistence
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.CommandPayload
import to.sava.peranta.model.CommandType
import to.sava.peranta.model.FilePayload
import to.sava.peranta.model.MessagePayload
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.model.decodeEnvelope
import to.sava.peranta.model.newPayloadId
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.net.NtfyClient
import to.sava.peranta.net.NtfyEvent
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ErrorKind
import to.sava.peranta.timeline.ReceivedFile
import to.sava.peranta.timeline.ReceivedMessage
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineFeed
import to.sava.peranta.timeline.TimelineItem

/** keyId 不一致時にタイムラインへ出す文言。 */
private const val KEY_MISMATCH_MESSAGE =
    "暗号鍵が一致しません。ペアリングをやり直してください"

/** 重複排除で記憶する payload.id の上限。剪定上限と揃える。 */
private const val DEDUPE_CAPACITY = 1000

/**
 * Envelope デコード → 復号 → 宛先/失効検証 → タイムライン反映までの受信中核。
 * Envelope の入手経路は問わない。Desktop は [ntfy] の WebSocket 購読（[start]）で、
 * Android は UnifiedPush のコールバックから [loadHistory] + [handleEvent] を直接呼んで駆動する。
 * UnifiedPush 駆動時は購読しないため [ntfy] は null でよい。
 * 各段階を kermit で構造化ログに残す。本文は info に出さず debug のみとする（§16）。
 *
 * [persistSensitiveHistory] が false（既定）のとき、OTP・SMS の本文は履歴保存時に伏せる（§11）。
 * 表示用の [items] と [onItemAppended] には伏せ字前の本文を渡し、永続のみを伏せる。
 *
 * [commandExecutor] を渡した端末では、自分宛で未失効の command ペイロードを実行する（§3.4）。
 * null の端末（コマンド実行に対応しない受信専用端末など）は command を無視する。
 */
class ReceivePipeline(
    private val ntfy: NtfyClient?,
    private val cipher: MessageCipher,
    private val feed: TimelineFeed,
    private val deviceId: String,
    private val commandExecutor: CommandExecutor? = null,
    private val persistSensitiveHistory: Boolean = false,
    private val log: Logger = Logger.withTag("Receive"),
    private val now: () -> Long = ::nowEpochMillis,
    private val onItemAppended: (TimelineItem) -> Unit = {},
    /** Envelope 解釈の前に生メッセージを検査し、true なら破棄する（自己疎通テストのマーカー等）。 */
    private val interceptRawMessage: (rawMessage: String) -> Boolean = { false },
) {

    /** 現在のタイムライン。UI はこれを購読する。 */
    val items: StateFlow<List<TimelineItem>> = feed.items

    /** 受信済み payload.id の集合。FIFO で [DEDUPE_CAPACITY] に丸める。 */
    private val seenIds = LinkedHashSet<String>()

    /**
     * 保存済み履歴を読み込み、タイムラインと重複排除の初期状態を作る。購読は行わない。
     * UnifiedPush 駆動時はメッセージ処理の前にこれを呼び、履歴に対する重複排除を効かせる。
     */
    suspend fun loadHistory() {
        val history = feed.load(now())
        history.forEach { rememberId(it.id) }
        log.i { "receive pipeline primed: history=${history.size}" }
    }

    /** 保存済み履歴を読み込み、[topic] の購読を開始する。呼び出しはキャンセルまで戻らない。 */
    suspend fun start(topic: String) {
        val client = ntfy ?: error("ntfy client is required to subscribe")
        loadHistory()
        log.i { "receive pipeline started: topic=$topic" }
        client.subscribe(topic).collect { handleEvent(it) }
    }

    /** 1 件の受信イベントを処理する（テストからも直接呼ぶ）。 */
    suspend fun handleEvent(event: NtfyEvent) {
        log.i { "event received: id=${event.id} topic=${event.topic}" }

        if (interceptRawMessage(event.message)) {
            log.d { "event intercepted: id=${event.id}" }
            return
        }

        val envelope = try {
            decodeEnvelope(event.message)
        } catch (e: SerializationException) {
            recordError(ErrorKind.ENVELOPE_DECODE, "エンベロープの解析に失敗しました", cause = e)
            return
        }

        val payload = try {
            cipher.open(envelope)
        } catch (e: KeyIdMismatchException) {
            recordError(ErrorKind.KEY_ID_MISMATCH, KEY_MISMATCH_MESSAGE, cause = e)
            return
        } catch (e: DecryptionException) {
            recordError(ErrorKind.DECRYPTION, "通知の復号に失敗しました", cause = e)
            return
        } catch (e: SerializationException) {
            recordError(ErrorKind.UNKNOWN_TYPE, "未知の通知種別を受信しました", causeLabel = e::class.simpleName)
            return
        }
        log.d { "decrypted payload id=${payload.id} type=${payload::class.simpleName}" }

        if (!isForMe(payload)) {
            log.d { "dropping payload id=${payload.id}: not addressed to us (to=${payload.to})" }
            return
        }

        if (isExpired(payload)) {
            log.i { "dropping payload id=${payload.id}: expired" }
            return
        }

        when (payload) {
            is NotificationPayload, is SmsPayload -> {
                if (!rememberId(payload.id)) {
                    log.d { "dropping duplicate payload id=${payload.id}" }
                    return
                }
                appendReceived(payload)
            }

            is FilePayload -> {
                if (!rememberId(payload.id)) {
                    log.d { "dropping duplicate payload id=${payload.id}" }
                    return
                }
                appendReceivedFile(payload)
            }

            is MessagePayload -> {
                if (!rememberId(payload.id)) {
                    log.d { "dropping duplicate payload id=${payload.id}" }
                    return
                }
                appendReceivedMessage(payload)
            }

            is CommandPayload -> executeCommand(payload)

            else -> log.d { "ignoring payload id=${payload.id} type=${payload::class.simpleName} (not displayed)" }
        }
    }

    /**
     * 自分宛で未失効の command を実行する（§3.4）。[commandExecutor] が無ければ何もしない。
     * 同一 id の再送で操作を二重発火しないよう、実行前に重複排除する。
     * 実行失敗（[CommandExecutionException]）はタイムラインへエラーとして記録する。
     */
    private suspend fun executeCommand(payload: CommandPayload) {
        val executor = commandExecutor ?: run {
            log.d { "no command executor; ignoring command id=${payload.id}" }
            return
        }
        if (!rememberId(payload.id)) {
            log.d { "dropping duplicate command id=${payload.id}" }
            return
        }
        try {
            dispatchCommand(executor, payload)
            log.i { "command executed id=${payload.id} command=${payload.command}" }
        } catch (e: CommandExecutionException) {
            recordError(ErrorKind.COMMAND_EXECUTION, e.message ?: "コマンドの実行に失敗しました", cause = e)
        }
    }

    /** command 種別ごとに必須フィールドを検証し、[executor] の対応メソッドへ委ねる。 */
    private suspend fun dispatchCommand(executor: CommandExecutor, payload: CommandPayload) {
        when (payload.command) {
            CommandType.DISMISS -> executor.dismiss(requireKey(payload))

            CommandType.INVOKE_ACTION ->
                executor.invokeAction(requireKey(payload), requireActionIndex(payload))

            CommandType.REPLY ->
                executor.reply(requireKey(payload), requireActionIndex(payload), requireReplyText(payload))

            CommandType.MUTE_APP -> executor.muteApp(requirePackageName(payload))

            CommandType.UNMUTE_APP -> executor.unmuteApp(requirePackageName(payload))
        }
    }

    private fun requireKey(payload: CommandPayload): String =
        payload.targetNotificationKey
            ?: throw CommandExecutionException("${payload.command} コマンドに対象通知キーがありません")

    private fun requireActionIndex(payload: CommandPayload): Int =
        payload.actionIndex
            ?: throw CommandExecutionException("${payload.command} コマンドにアクション番号がありません")

    private fun requireReplyText(payload: CommandPayload): String =
        payload.replyText
            ?: throw CommandExecutionException("${payload.command} コマンドに返信本文がありません")

    private fun requirePackageName(payload: CommandPayload): String =
        payload.packageName
            ?: throw CommandExecutionException("${payload.command} コマンドにパッケージ名がありません")

    private fun isForMe(payload: Payload): Boolean =
        payload.to == BROADCAST_TARGET || payload.to == deviceId

    private fun isExpired(payload: Payload): Boolean {
        val expiresAt = expiresAtOf(payload) ?: return false
        return expiresAt < now()
    }

    private fun expiresAtOf(payload: Payload): Long? = when (payload) {
        is NotificationPayload -> payload.expiresAtEpochMillis
        is SmsPayload -> payload.expiresAtEpochMillis
        is FilePayload -> payload.expiresAtEpochMillis
        is CommandPayload -> payload.expiresAtEpochMillis
        else -> null
    }

    /** [id] を記憶し、初出なら true・既知なら false を返す。上限超過分は FIFO で淘汰する。 */
    private fun rememberId(id: String): Boolean {
        if (!seenIds.add(id)) return false
        if (seenIds.size > DEDUPE_CAPACITY) {
            val oldest = seenIds.iterator().next()
            seenIds.remove(oldest)
        }
        return true
    }

    private suspend fun appendReceived(payload: Payload) {
        val expiresAt = expiresAtOf(payload)
        val displayItem = ReceivedNotification(
            id = payload.id,
            timestampEpochMillis = now(),
            payload = payload,
            expiresAtEpochMillis = expiresAt,
        )
        record(displayItem = displayItem, persistItem = persistItemFor(displayItem, payload))
        log.i { "notification appended id=${payload.id}" }
    }

    /**
     * 受信した画像・ファイル転送（§4.3）をタイムラインへ [ReceivedFile] として追記する。
     * 受信時点では本体をダウンロードせず、参照だけを載せる（判断4）。
     */
    private suspend fun appendReceivedFile(payload: FilePayload) {
        val displayItem = ReceivedFile(
            id = payload.id,
            timestampEpochMillis = now(),
            payload = payload,
            expiresAtEpochMillis = payload.expiresAtEpochMillis,
        )
        record(displayItem = displayItem, persistItem = persistFileItemFor(displayItem, payload))
        log.i { "received file appended id=${payload.id} attachments=${payload.attachments.size}" }
    }

    /**
     * 受信したメッセージ（§4.1 message）をタイムラインへ [ReceivedMessage] として追記する。
     * 伏せ字（§11）・通知フィルタ（§7）の対象外のため、[payloadForPersistence] を経由せず表示用アイテムを
     * そのまま永続する。
     */
    private suspend fun appendReceivedMessage(payload: MessagePayload) {
        val displayItem = ReceivedMessage(
            id = payload.id,
            timestampEpochMillis = now(),
            payload = payload,
        )
        record(displayItem = displayItem)
        log.i { "message appended id=${payload.id}" }
    }

    /**
     * 保存用アイテムを組む。[persistSensitiveHistory] が false なら本文を伏せる（§11）。
     * 伏せる必要が無ければ表示用アイテムをそのまま保存に使う。
     * 伏せ字を適用する場合は、送信元が持たせた TEXT 添付（全文 blob 参照、§4.3）も併せて取り除く。
     * 残したままだと表示側の自動取得やダウンロードキャッシュ経由で伏せたはずの本文全文が漏れる。
     */
    private fun persistItemFor(displayItem: ReceivedNotification, payload: Payload): ReceivedNotification {
        val redacted = payloadForPersistence(payload, persistSensitiveHistory)
        return if (redacted === payload) displayItem else displayItem.copy(payload = withoutTextAttachments(redacted))
    }

    /**
     * ファイル転送の保存用アイテムを組む。[persistSensitiveHistory] が false ならキャプションを伏せる（§11）。
     * 添付メタ・ファイル名はダウンロードに要るため保持する。伏せる必要が無ければ表示用をそのまま保存に使う。
     * キャプションを伏せる場合は TEXT 添付も併せて取り除く（[persistItemFor] と同じ理由）。
     */
    private fun persistFileItemFor(displayItem: ReceivedFile, payload: FilePayload): ReceivedFile {
        val redacted = payloadForPersistence(payload, persistSensitiveHistory)
        return if (redacted === payload) displayItem else displayItem.copy(payload = withoutTextAttachments(redacted) as FilePayload)
    }

    /** 添付一覧から kind=TEXT（全文 blob 参照）のみを取り除く。画像・ファイル添付は伏せ字と無関係なため残す。 */
    private fun withoutTextAttachments(payload: Payload): Payload = when (payload) {
        is NotificationPayload -> payload.copy(attachments = payload.attachments.filterNotTextKind())
        is SmsPayload -> payload.copy(attachments = payload.attachments.filterNotTextKind())
        is FilePayload -> payload.copy(attachments = payload.attachments.filterNotTextKind())
        else -> payload
    }

    private fun List<AttachmentRef>.filterNotTextKind() = filterNot { it.kind == AttachmentKind.TEXT }

    private suspend fun recordError(
        kind: ErrorKind,
        message: String,
        cause: Throwable? = null,
        causeLabel: String? = null,
    ) {
        if (cause != null) {
            log.w(cause) { "receive error [$kind]: $message" }
        } else {
            log.w { "receive error [$kind]: $message${causeLabel?.let { " ($it)" } ?: ""}" }
        }
        record(
            ErrorItem(
                id = newPayloadId(),
                timestampEpochMillis = now(),
                message = message,
                kind = kind,
            ),
        )
    }

    /**
     * [persistItem] を永続化し、[displayItem] を表示（[items]・[onItemAppended]）へ流す。
     * 受信通知では [persistItem] を伏せ字適用後に、[displayItem] を伏せ字前にすることで、
     * 表示は本文を保ちつつ永続だけを伏せる（§11）。エラー等は両者が同一でよい。
     * 永続失敗の握り潰しとログ化は [feed] の契約（[TimelineFeed.record]）に委ねる。
     */
    private suspend fun record(displayItem: TimelineItem, persistItem: TimelineItem = displayItem) {
        feed.record(displayItem, persistItem)
        onItemAppended(displayItem)
    }
}
