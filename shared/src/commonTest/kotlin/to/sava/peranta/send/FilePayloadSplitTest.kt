package to.sava.peranta.send

import kotlinx.coroutines.test.runTest
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import to.sava.peranta.model.encodeEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilePayloadSplitTest {

    private fun ref(index: Int) = AttachmentRef(
        blobId = "01234567-89ab-4cde-8fed-01234567890$index",
        url = "https://peranta.sava.to/file/${"a".repeat(24)}$index",
        fileName = "IMG_2026_07_$index.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 3_145_728,
        kind = AttachmentKind.IMAGE,
        blobExpiresAtEpochMillis = 1_783_000_000_000,
        enc = BlobEnc(keyId = "k1", saltBase64 = "AAAAAAAAAAAAAAAAAAAAAA==", chunkSize = 1_048_576, totalChunks = 3),
    )

    /** 封緘サイズの見積り [forwardedEnvelopeSize] は、実際に暗号化した Envelope のバイト長と一致する。 */
    @Test
    fun envelopeSizeEstimateMatchesActual() = runTest {
        val payload = buildFilePayload(
            deviceId = "phone",
            attachments = listOf(ref(1), ref(2)),
            now = 2000,
            caption = "写真を共有しました",
        )
        val cipher = MessageCipher(generateKey(), "k1")
        val actual = encodeEnvelope(cipher.seal(payload)).encodeToByteArray().size
        assertEquals(actual, forwardedEnvelopeSize(payload, "k1"))
    }

    /** 添付が上限に収まる限り 1 つの FilePayload にまとめる。 */
    @Test
    fun keepsSingleWhenWithinBudget() {
        val payloads = buildFilePayloads(
            deviceId = "phone",
            attachments = listOf(ref(1)),
            keyId = "k1",
            now = 2000,
            caption = "写真",
        )
        assertEquals(1, payloads.size)
        assertEquals(1, payloads.single().attachments.size)
        assertEquals("写真", payloads.single().caption)
    }

    /**
     * 上限を小さくすると添付は複数の FilePayload に分割され、全添付が失われず、
     * 各 Envelope が上限に収まり、キャプションは先頭のみに載る（§4.3）。
     */
    @Test
    fun splitsAcrossPayloadsWithoutDroppingAttachments() {
        val attachments = (1..5).map { ref(it) }
        // 添付 1 件（+キャプション）ぶんに収まる上限にすると、複数添付は必ず分割される。
        val budget = forwardedEnvelopeSize(
            buildFilePayload(deviceId = "phone", attachments = listOf(ref(1)), now = 2000, caption = "まとめ"),
            "k1",
        )
        val payloads = buildFilePayloads(
            deviceId = "phone",
            attachments = attachments,
            keyId = "k1",
            now = 2000,
            caption = "まとめ",
            maxEnvelopeBytes = budget,
        )

        assertTrue(payloads.size > 1, "expected multiple payloads, got ${payloads.size}")

        val allBlobIds = payloads.flatMap { it.attachments }.map { it.blobId }
        assertEquals(attachments.map { it.blobId }, allBlobIds)

        payloads.forEach { payload ->
            assertTrue(payload.attachments.isNotEmpty(), "empty payload produced")
            assertTrue(
                forwardedEnvelopeSize(payload, "k1") <= budget,
                "payload envelope exceeded budget: ${forwardedEnvelopeSize(payload, "k1")}",
            )
        }

        assertEquals("まとめ", payloads.first().caption)
        payloads.drop(1).forEach { assertEquals(null, it.caption) }
    }

    /**
     * JSON エスケープで膨らむキャプションでも、封緘後サイズで先頭バッチが予算を超えないよう
     * キャプション側を追加で切り詰める（添付は落とさない、§4.3）。
     */
    @Test
    fun trimsCaptionSoFirstBatchFitsBudget() {
        val attachment = ref(1)
        // 引用符・改行だらけのキャプション。生 UTF-8 では小さいが JSON エスケープで約 2 倍に膨らむ。
        val caption = "\"\n".repeat(400)
        // 添付 1 件 + 短いキャプションぶんの予算。エスケープ後の長いキャプションはこのままでは収まらない。
        val budget = forwardedEnvelopeSize(
            buildFilePayload(deviceId = "phone", attachments = listOf(attachment), now = 2000, caption = "あ".repeat(20)),
            "k1",
        )
        val payloads = buildFilePayloads(
            deviceId = "phone",
            attachments = listOf(attachment),
            keyId = "k1",
            now = 2000,
            caption = caption,
            maxEnvelopeBytes = budget,
        )

        assertEquals(1, payloads.size)
        assertEquals(1, payloads.single().attachments.size)
        val trimmed = payloads.single().caption
        assertNotNull(trimmed)
        assertTrue(
            trimmed.encodeToByteArray().size < caption.encodeToByteArray().size,
            "expected caption to be further trimmed",
        )
        assertTrue(
            forwardedEnvelopeSize(payloads.single(), "k1") <= budget,
            "first batch envelope exceeded budget after caption trim",
        )
    }

    /**
     * 予算が添付 1 件ぶんちょうどで、どんなキャプションも載らない場合はキャプションを外す。
     * 添付は落とさず単独ペイロードで送る（§4.3）。
     */
    @Test
    fun dropsCaptionWhenOnlyAttachmentFitsBudget() {
        val attachment = ref(1)
        val budget = forwardedEnvelopeSize(
            buildFilePayload(deviceId = "phone", attachments = listOf(attachment), now = 2000),
            "k1",
        )
        val payloads = buildFilePayloads(
            deviceId = "phone",
            attachments = listOf(attachment),
            keyId = "k1",
            now = 2000,
            caption = "どうしても載らないキャプション",
            maxEnvelopeBytes = budget,
        )
        assertEquals(1, payloads.size)
        assertEquals(1, payloads.single().attachments.size)
        assertNull(payloads.single().caption)
        assertTrue(forwardedEnvelopeSize(payloads.single(), "k1") <= budget)
    }

    /**
     * 上限が添付 1 件分にも満たない病的な設定でも、各添付は単独ペイロードに載って送られる
     * （サイレントに落とさない）。
     */
    @Test
    fun oversizedAttachmentGoesIntoOwnPayload() {
        val attachments = listOf(ref(1), ref(2))
        val payloads = buildFilePayloads(
            deviceId = "phone",
            attachments = attachments,
            keyId = "k1",
            now = 2000,
            maxEnvelopeBytes = 1,
        )
        assertEquals(2, payloads.size)
        assertEquals(attachments.map { it.blobId }, payloads.flatMap { it.attachments }.map { it.blobId })
    }
}
