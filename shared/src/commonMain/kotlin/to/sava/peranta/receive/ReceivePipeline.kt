package to.sava.peranta.receive

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerializationException
import to.sava.peranta.crypto.DecryptionException
import to.sava.peranta.crypto.KeyIdMismatchException
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.model.BROADCAST_TARGET
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
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineItem
import to.sava.peranta.timeline.TimelineStore

/** keyId 不一致時にタイムラインへ出す文言。 */
private const val KEY_MISMATCH_MESSAGE =
    "暗号鍵が一致しません。ペアリングをやり直してください"

/** 重複排除で記憶する payload.id の上限。剪定上限と揃える。 */
private const val DEDUPE_CAPACITY = 1000

/**
 * ntfy 購読 → Envelope デコード → 復号 → 宛先/失効検証 → タイムライン反映までの受信パイプライン。
 * 各段階を kermit で構造化ログに残す。本文は info に出さず debug のみとする（§16）。
 */
class ReceivePipeline(
    private val ntfy: NtfyClient,
    private val cipher: MessageCipher,
    private val store: TimelineStore,
    private val deviceName: String,
    private val log: Logger = Logger.withTag("Receive"),
    private val now: () -> Long = ::nowEpochMillis,
    private val onItemAppended: (TimelineItem) -> Unit = {},
) {

    private val _items = MutableStateFlow<List<TimelineItem>>(emptyList())

    /** 現在のタイムライン。UI はこれを購読する。 */
    val items: StateFlow<List<TimelineItem>> = _items.asStateFlow()

    /** 受信済み payload.id の集合。FIFO で [DEDUPE_CAPACITY] に丸める。 */
    private val seenIds = LinkedHashSet<String>()

    /** 保存済み履歴を読み込み、[topic] の購読を開始する。呼び出しはキャンセルまで戻らない。 */
    suspend fun start(topic: String) {
        val history = store.loadAll()
        _items.value = history
        history.forEach { rememberId(it.id) }
        log.i { "receive pipeline started: topic=$topic, history=${history.size}" }
        ntfy.subscribe(topic).collect { handleEvent(it) }
    }

    /** 1 件の受信イベントを処理する（テストからも直接呼ぶ）。 */
    suspend fun handleEvent(event: NtfyEvent) {
        log.i { "event received: id=${event.id} topic=${event.topic}" }

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

            else -> log.d { "ignoring payload id=${payload.id} type=${payload::class.simpleName} (not displayed in M3)" }
        }
    }

    private fun isForMe(payload: Payload): Boolean =
        payload.to == BROADCAST_TARGET || payload.to == deviceName

    private fun isExpired(payload: Payload): Boolean {
        val expiresAt = when (payload) {
            is NotificationPayload -> payload.expiresAtEpochMillis
            is SmsPayload -> payload.expiresAtEpochMillis
            else -> null
        }
        return expiresAt != null && expiresAt < now()
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
        val expiresAt = when (payload) {
            is NotificationPayload -> payload.expiresAtEpochMillis
            is SmsPayload -> payload.expiresAtEpochMillis
            else -> null
        }
        val item = ReceivedNotification(
            id = payload.id,
            timestampEpochMillis = now(),
            payload = payload,
            expiresAtEpochMillis = expiresAt,
        )
        record(item)
        log.i { "notification appended id=${payload.id}" }
    }

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

    private suspend fun record(item: TimelineItem) {
        try {
            store.append(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e { "failed to persist timeline item id=${item.id} (${e::class.simpleName})" }
        }
        _items.update { it + item }
        onItemAppended(item)
    }
}
