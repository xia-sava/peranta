package to.sava.peranta.receive

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerializationException
import to.sava.peranta.crypto.DecryptionException
import to.sava.peranta.crypto.KeyIdMismatchException
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.crypto.UnsupportedEnvelopeVersionException
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
import to.sava.peranta.model.notificationKeyOrNull
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.model.revisionOrZero
import to.sava.peranta.net.NtfyClient
import to.sava.peranta.net.NtfyEvent
import to.sava.peranta.platform.topicForLog
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ErrorKind
import to.sava.peranta.timeline.ErrorSuppressor
import to.sava.peranta.timeline.ReceivedFile
import to.sava.peranta.timeline.ReceivedMessage
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineFeed
import to.sava.peranta.timeline.TimelineItem

/** keyId 不一致時にタイムラインへ出す文言。 */
private const val KEY_MISMATCH_MESSAGE =
    "暗号鍵が一致しません。ペアリングをやり直してください"

/** 送信側の封筒の版が新しく開けない場合にタイムラインへ出す文言。 */
private const val UNSUPPORTED_ENVELOPE_VERSION_MESSAGE =
    "送信側の端末が新しい版です。この端末を更新してください"

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
 *
 * この系の信頼は共有鍵の所持だけに基づく。鍵を持つ端末は互いを信頼するため、payload の `from` は
 * 自己申告であって暗号学的な認証を伴わない。[isForMe] の宛先検証は配送先を絞るものであり、
 * 別の端末を騙る payload を見分けるものではない。
 *
 * 復号前の入力から生じるエラーは鍵を持たない第三者でも任意回数起こせるため、タイムラインへの
 * 追記は [ErrorSuppressor] の時間枠で抑える（§10.5）。抑えるのは 2 件目以降だけで、
 * 各枠の 1 件目は必ず出す。
 *
 * 復号を通った payload は、表示・永続化の前に [normalizeReceivedPayload] でワイヤ形式の約束へ
 * 収め直す（§4）。上限超過は拒否ではなく切り詰めで受け入れるため、この上限を知らない旧バージョンの
 * 送信端末の通知も消えない。
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
    /**
     * 既存アイテムが改版で差し替わったときに呼ぶ（§4.3.1）。表示中のトーストへの画像の差し込みや、
     * OS 通知の出し直しに使う。新規追記（[onItemAppended]）とは排他で、既読同期による再記録では呼ばない。
     */
    private val onItemUpdated: (TimelineItem) -> Unit = {},
    /** Envelope 解釈の前に生メッセージを検査し、true なら破棄する（自己疎通テストのマーカー等）。 */
    private val interceptRawMessage: (rawMessage: String) -> Boolean = { false },
) {

    /** 現在のタイムライン。UI はこれを購読する。 */
    val items: StateFlow<List<TimelineItem>> = feed.items

    /** 受信済み payload.id の集合。FIFO で [DEDUPE_CAPACITY] に丸める。 */
    private val seenIds = LinkedHashSet<String>()

    /** エラー追記の抑止窓（§10.5）。種別ごとの性質は [ErrorKind.origin] が持つ。 */
    private val errorSuppressor = ErrorSuppressor()

    /**
     * 保存済み履歴を読み込み、タイムラインと重複排除の初期状態を作る。購読は行わない。
     * UnifiedPush 駆動時はメッセージ処理の前にこれを呼び、履歴に対する重複排除を効かせる。
     */
    suspend fun loadHistory() {
        val history = feed.load(now())
        history.forEach { item ->
            rememberId(item.id)
            // 改版済みで保存された通知は、その改版のキーも既知にして再適用を防ぐ（§4.3.1）。
            (item as? ReceivedNotification)?.let { rememberId(dedupeKeyOf(it.payload)) }
        }
        log.i { "receive pipeline primed: history=${history.size}" }
    }

    /** 保存済み履歴を読み込み、[topic] の購読を開始する。呼び出しはキャンセルまで戻らない。 */
    suspend fun start(topic: String) {
        val client = ntfy ?: error("ntfy client is required to subscribe")
        loadHistory()
        log.i { "receive pipeline started: topic=${topicForLog(topic)}" }
        client.subscribe(topic).collect { handleEvent(it) }
    }

    /** 1 件の受信イベントを処理する（テストからも直接呼ぶ）。 */
    suspend fun handleEvent(event: NtfyEvent) {
        log.i { "event received: id=${event.id} topic=${topicForLog(event.topic)}" }

        if (interceptRawMessage(event.message)) {
            log.d { "event intercepted: id=${event.id}" }
            return
        }

        val envelope = try {
            decodeEnvelope(event.message)
        } catch (e: SerializationException) {
            recordError(ErrorKind.ENVELOPE_DECODE, "エンベロープの解析に失敗しました", causeLabel = e::class.simpleName)
            return
        }

        val decrypted = try {
            cipher.open(envelope)
        } catch (e: UnsupportedEnvelopeVersionException) {
            recordError(
                ErrorKind.UNSUPPORTED_ENVELOPE_VERSION,
                UNSUPPORTED_ENVELOPE_VERSION_MESSAGE,
                causeLabel = e::class.simpleName,
            )
            return
        } catch (e: KeyIdMismatchException) {
            recordError(ErrorKind.KEY_ID_MISMATCH, KEY_MISMATCH_MESSAGE, causeLabel = e::class.simpleName)
            return
        } catch (e: DecryptionException) {
            recordError(ErrorKind.DECRYPTION, "通知の復号に失敗しました", causeLabel = e::class.simpleName)
            return
        } catch (e: SerializationException) {
            recordError(ErrorKind.UNKNOWN_TYPE, "未知の通知種別を受信しました", causeLabel = e::class.simpleName)
            return
        }
        log.d { "decrypted payload id=${decrypted.id} type=${decrypted::class.simpleName}" }

        if (!isForMe(decrypted)) {
            log.d { "dropping payload id=${decrypted.id}: not addressed to us (to=${decrypted.to})" }
            return
        }

        if (isExpired(decrypted)) {
            log.i { "dropping payload id=${decrypted.id}: expired" }
            return
        }

        val payload = normalizeReceivedPayload(decrypted)

        when (payload) {
            is NotificationPayload, is SmsPayload -> {
                if (!rememberId(dedupeKeyOf(payload))) {
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
     * 実行失敗（[CommandExecutionException]）と、自端末が転送していない通知への操作の拒否
     * （[CommandUnauthorizedException]）は、それぞれ別種のエラーとしてタイムラインへ記録する。
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
        } catch (e: CommandUnauthorizedException) {
            recordError(
                ErrorKind.COMMAND_UNAUTHORIZED,
                e.message ?: "この端末が転送していない通知は操作できません",
                causeLabel = e::class.simpleName,
            )
        } catch (e: CommandExecutionException) {
            recordError(
                ErrorKind.COMMAND_EXECUTION,
                e.message ?: "コマンドの実行に失敗しました",
                causeLabel = e::class.simpleName,
            )
        }
    }

    /**
     * command 種別ごとに必須フィールドを検証し、[executor] の対応メソッドへ委ねる。
     *
     * DISMISS では「元通知は消えた」の印（[markSourceDismissed]）を取り下げの前に付ける。
     * 印は自端末の表示に対する更新で、元通知を実際に取り下げられたかとは独立して成り立つ
     * （NLS 未接続の端末でも印は付く）。取り下げ側が例外を投げても印は残る。
     */
    private suspend fun dispatchCommand(executor: CommandExecutor, payload: CommandPayload) {
        when (payload.command) {
            CommandType.DISMISS -> {
                val key = requireKey(payload)
                markSourceDismissed(key)
                executor.dismiss(key)
            }

            CommandType.INVOKE_ACTION ->
                executor.invokeAction(requireKey(payload), requireActionIndex(payload))

            CommandType.REPLY ->
                executor.reply(requireKey(payload), requireActionIndex(payload), requireReplyText(payload))

            CommandType.MUTE_APP -> executor.muteApp(requirePackageName(payload))

            CommandType.UNMUTE_APP -> executor.unmuteApp(requirePackageName(payload))
        }
    }

    /**
     * [notificationKey] に対応する受信通知（未マーク分すべて）を「元通知は消えた」状態にマークし、
     * 伏せ字処理（[persistItemFor]）を通して再記録する（§3.4）。同一 key で複数回再投稿された
     * 通知（Google Messages 等）がタイムラインに積まれていても、全件をマークする。
     * DISMISS コマンドの受信時のほか、自端末での「送信元の通知を消す」操作（プラットフォーム側の
     * 配線）からも呼ばれる。
     * 対象が見つからない、または全件マーク済みなら何もしない。
     */
    suspend fun markSourceDismissed(notificationKey: String) {
        val targets = items.value.asSequence()
            .filterIsInstance<ReceivedNotification>()
            .filter { it.payload.notificationKeyOrNull() == notificationKey && !it.sourceDismissed }
            .toList()
        if (targets.isEmpty()) return
        targets.forEach { target ->
            val marked = target.copy(sourceDismissed = true)
            record(displayItem = marked, persistItem = persistItemFor(marked, marked.payload))
        }
        log.i { "notification marked source-dismissed key=$notificationKey count=${targets.size}" }
    }

    /**
     * [itemId] の受信通知をこの端末のタイムラインから消す（§10.1）。伏せ字処理（[persistItemFor]）を
     * 通して再記録し、表示から外す。実体は剪定で落ちる（§11）。
     * 在メモリには残すため、他端末からの dismiss（§3.4）は消したあとも自端末の通知へ届く。
     * 対象が見つからない、または既に消し済みなら何もしない。
     */
    suspend fun hideFromTimeline(itemId: String) {
        val target = items.value.asSequence()
            .filterIsInstance<ReceivedNotification>()
            .firstOrNull { it.id == itemId && !it.hiddenFromTimeline } ?: return
        val hidden = target.copy(hiddenFromTimeline = true)
        record(displayItem = hidden, persistItem = persistItemFor(hidden, hidden.payload))
        log.i { "notification hidden from timeline id=$itemId" }
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

    /**
     * 受信通知をタイムラインへ載せる。改版（§4.3.1）で差し替える既存アイテムがあるときは、受信時刻と
     * 元通知消滅の印を保ったまま payload だけ入れ替える。差し替え先が無ければ通常の新規追記として扱う
     * （初回配送が届かなかった場合でも改版だけで表示できる）。
     * 配送順が入れ替わって古い改版が後から届いたときは、既に載っている新しい内容を守るため捨てる。
     */
    private suspend fun appendReceived(payload: Payload) {
        val existing = existingNotification(payload.id)
        if (revisionOf(existing?.payload) > revisionOf(payload)) {
            log.d { "dropping superseded payload id=${payload.id} revision=${revisionOf(payload)}" }
            return
        }
        val revised = existing?.takeIf { revisionOf(payload) > 0 }
        val displayItem = revised?.copy(payload = payload, expiresAtEpochMillis = expiresAtOf(payload))
            ?: ReceivedNotification(
                id = payload.id,
                timestampEpochMillis = now(),
                payload = payload,
                expiresAtEpochMillis = expiresAtOf(payload),
            )
        record(
            displayItem = displayItem,
            persistItem = persistItemFor(displayItem, payload),
            updated = revised != null,
        )
        val outcome = if (revised != null) "revised" else "appended"
        log.i { "notification $outcome id=${payload.id} revision=${revisionOf(payload)}" }
    }

    /** タイムラインに載っている同一 id の受信通知。無ければ null。 */
    private fun existingNotification(payloadId: String): ReceivedNotification? =
        items.value.asSequence()
            .filterIsInstance<ReceivedNotification>()
            .firstOrNull { it.id == payloadId }

    /** 重複排除キー。改版された通知だけ id と revision の対で見る（§4.3.1）。 */
    private fun dedupeKeyOf(payload: Payload): String =
        when (val revision = revisionOf(payload)) {
            0 -> payload.id
            else -> "${payload.id}#$revision"
        }

    private fun revisionOf(payload: Payload?): Int = payload?.revisionOrZero() ?: 0

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

    /**
     * エラーをタイムラインへ載せる。外部から任意回数誘発できる種別が際限なく積まれないよう、
     * [errorSuppressor] の時間枠を通してから追記する（§10.5）。抑止した分は次に通す 1 件のログへ
     * 件数として畳み込み、追記そのものは行わない。
     * 例外は種別名だけを残す。ktor の例外メッセージには接続先 URL（＝topic）が載るため、
     * スタックトレースごとログへ流さない（§16）。
     */
    private suspend fun recordError(
        kind: ErrorKind,
        message: String,
        causeLabel: String? = null,
    ) {
        val at = now()
        if (!errorSuppressor.allows(kind, message, at)) {
            return
        }
        val suppressed = errorSuppressor.takeSuppressedCount(kind, message)
        log.w {
            "receive error [$kind]: $message${causeLabel?.let { " ($it)" } ?: ""}" +
                if (suppressed > 0) " (suppressed $suppressed since last report)" else ""
        }
        record(
            ErrorItem(
                id = newPayloadId(),
                timestampEpochMillis = at,
                message = message,
                kind = kind,
            ),
        )
    }

    /**
     * [persistItem] を永続化し、[displayItem] を表示（[items]）へ流す。
     * 受信通知では [persistItem] を伏せ字適用後に、[displayItem] を伏せ字前にすることで、
     * 表示は本文を保ちつつ永続だけを伏せる（§11）。エラー等は両者が同一でよい。
     * 永続失敗の握り潰しとログ化は [feed] の契約（[TimelineFeed.record]）に委ねる。
     * [onItemAppended] は新規追記のときだけ呼ぶ。同一 id の置換（[markSourceDismissed] 等による
     * 再記録）では呼ばないため、既存アイテムの再表示（トースト・ミラー通知の再発火）は起きない。
     * [updated] を立てた置換（改版の反映、§4.3.1）だけは [onItemUpdated] で表示の更新を促す。
     */
    private suspend fun record(
        displayItem: TimelineItem,
        persistItem: TimelineItem = displayItem,
        updated: Boolean = false,
    ) {
        val appended = feed.record(displayItem, persistItem)
        when {
            appended -> onItemAppended(displayItem)
            updated -> onItemUpdated(displayItem)
        }
    }
}
