package to.sava.peranta.send

import kotlinx.coroutines.test.runTest
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.filter.FilterMode
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.encodeEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForwardedEnvelopeSizeTest {

    /** ntfy 既定の message-size-limit（bytes）。 */
    private val ntfyMessageSizeLimit = 4096

    /** server.yml で引き上げた message-size-limit（16k、§4.3）。添付参照付き通知はこの余裕内に収める。 */
    private val raisedMessageSizeLimit = 16 * 1024

    /**
     * UnifiedPush 仕様が配送元に保証する最小メッセージサイズ（bytes）。
     * Desktop の WebSocket 購読は server.yml の [raisedMessageSizeLimit] まで受けられるが、
     * Android タブレットは UnifiedPush 受信のため、この実質上限を超える envelope は経路上で欠落し得る。
     */
    private val unifiedPushGuaranteedLimit = 4096

    private fun cipher() = MessageCipher(generateKey(), "k1")

    private fun input(title: String, text: String) = NotificationInput(
        packageName = "com.example.chat",
        appName = "Chat",
        title = title,
        text = text,
        notificationKey = "0|com.example.chat|1|null|10",
        postedAtEpochMillis = 1000,
    )

    private fun build(title: String, text: String) = buildNotificationPayload(
        input(title = title, text = text),
        mode = FilterMode.DENYLIST,
        rules = emptyList(),
        deviceId = "phone",
        now = 2000,
    )!!

    /** 日本語 900 文字超の本文でも、封緘後の Envelope は ntfy 既定上限 4096 bytes に収まる。 */
    @Test
    fun longJapaneseBodyFitsWithinNtfyLimit() = runTest {
        val payload = build(title = "重要なお知らせ".repeat(50), text = "あ".repeat(1000))
        val body = encodeEnvelope(cipher().seal(payload))
        val size = body.encodeToByteArray().size
        assertTrue(size <= ntfyMessageSizeLimit, "envelope was $size bytes")
    }

    /** 絵文字を含む本文は転送時の切り詰めでサロゲートペアが壊れず、封緘・開封を往復できる。 */
    @Test
    fun emojiBodySurvivesForwarding() = runTest {
        val payload = build(title = "🎉", text = "🙂".repeat(2000))
        val core = payload.text.removeSuffix("…")
        assertTrue(core.isNotEmpty())
        assertEquals(0, core.length % "🙂".length)
        assertTrue(core.chunked("🙂".length).all { it == "🙂" })

        val cipher = cipher()
        val restored = cipher.open(cipher.seal(payload))
        assertTrue(restored.toString().contains("🙂"))
    }

    private fun attachmentRef(index: Int) = AttachmentRef(
        blobId = "00000000-0000-0000-0000-00000000000$index",
        url = "https://peranta.sava.to/file/${"a".repeat(24)}$index",
        fileName = "IMG_2026_07_$index.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 3_145_728,
        kind = AttachmentKind.IMAGE,
        blobExpiresAtEpochMillis = 1_783_000_000_000,
        enc = BlobEnc(
            keyId = "k1",
            saltBase64 = "AAAAAAAAAAAAAAAAAAAAAA==",
            chunkSize = 1_048_576,
            totalChunks = 3,
        ),
    )

    /** 添付参照を数件伴う通知でも、引き上げ後の message-size-limit（16k）に十分収まる（§4.3）。 */
    @Test
    fun notificationWithAttachmentsFitsWithinRaisedLimit() = runTest {
        val payload = NotificationPayload(
            id = "id-1",
            from = "phone",
            to = "*",
            sentAtEpochMillis = 1000,
            packageName = "com.example.gallery",
            appName = "Gallery",
            title = "写真が届きました",
            text = "3 枚の画像を共有しました",
            notificationKey = "0|com.example.gallery|1|null|10",
            postedAtEpochMillis = 900,
            attachments = List(3) { attachmentRef(it) },
        )
        val size = encodeEnvelope(cipher().seal(payload)).encodeToByteArray().size
        assertTrue(size <= raisedMessageSizeLimit, "envelope with attachments was $size bytes")
    }

    /**
     * UnifiedPush の実質上限に対する添付参照 1 件分の実サイズ。
     * blobId は UUID（36 文字）、url は ntfy のダウンロード URL、fileName は日本語ファイル名で
     * 一般的なファイルシステムの上限（255 バイト）近辺、mimeType は実在する長めの登録 MIME、
     * enc は BlobEnc の実フィールドを使う。
     */
    private fun worstCaseAttachmentRef() = AttachmentRef(
        blobId = "01234567-89ab-4cde-8fed-0123456789ab",
        url = "https://peranta.sava.to/file/${"a".repeat(24)}",
        fileName = "あ".repeat(83) + ".jpeg",
        mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        sizeBytes = 1_073_741_824,
        kind = AttachmentKind.IMAGE,
        blobExpiresAtEpochMillis = 1_800_000_000_000,
        enc = BlobEnc(
            keyId = "1",
            saltBase64 = "AAAAAAAAAAAAAAAAAAAAAA==",
            chunkSize = 1_048_576,
            totalChunks = 1024,
        ),
    )

    /**
     * 添付参照 1 件を伴う通知は、タイトル・本文を切り詰め予算（300/2000 バイト）いっぱいまで使うと、
     * UnifiedPush の実質上限（4096 バイト）を超える。§4.3 の添付付き転送は UP 経路（Android タブレット）では
     * 現行の切り詰め予算のみでは収まらず、AttachmentRef の圧縮や fileName のさらなる切り詰めなど、
     * 別の予算設計が必要になる。
     */
    @Test
    fun notificationWithAttachmentExceedsUnifiedPushLimit() = runTest {
        val payload = NotificationPayload(
            id = "id-1",
            from = "phone",
            to = "*",
            sentAtEpochMillis = 1000,
            packageName = "com.example.gallery",
            appName = "Gallery",
            title = truncateForForwarding("写真が届きました".repeat(50), MAX_FORWARDED_TITLE_BYTES),
            text = truncateForForwarding("3 枚の画像を共有しました".repeat(200), MAX_FORWARDED_TEXT_BYTES),
            notificationKey = "0|com.example.gallery|1|null|10",
            postedAtEpochMillis = 900,
            attachments = listOf(worstCaseAttachmentRef()),
        )
        val size = encodeEnvelope(cipher().seal(payload)).encodeToByteArray().size
        assertTrue(
            size > unifiedPushGuaranteedLimit,
            "envelope with a single attachment was $size bytes; expected it to exceed the " +
                "UnifiedPush guaranteed limit of $unifiedPushGuaranteedLimit bytes",
        )
    }
}
