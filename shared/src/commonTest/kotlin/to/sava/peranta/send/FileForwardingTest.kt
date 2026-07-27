package to.sava.peranta.send

import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.BlobEnc
import to.sava.peranta.model.Priority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileForwardingTest {

    private fun ref() = AttachmentRef(
        blobId = "b1",
        url = "https://peranta.example.com/file/abc",
        fileName = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1024,
        kind = AttachmentKind.IMAGE,
        enc = BlobEnc(keyId = "k1", saltBase64 = "AAAAAAAAAAAAAAAAAAAAAA==", chunkSize = 1_048_576, totalChunks = 1),
    )

    /** キャプションと添付から FilePayload を組み、宛先は全端末にする。 */
    @Test
    fun buildsFilePayloadWithCaption() {
        val payload = buildFilePayload(
            deviceId = "phone",
            attachments = listOf(ref()),
            now = 5000,
            caption = "旅行の写真",
            idGen = { "id-1" },
        )
        assertEquals("id-1", payload.id)
        assertEquals("phone", payload.from)
        assertEquals(BROADCAST_TARGET, payload.to)
        assertEquals("旅行の写真", payload.caption)
        assertEquals(1, payload.attachments.size)
        assertEquals(5000, payload.postedAtEpochMillis)
    }

    /** 空・空白のキャプションは null に落とす。 */
    @Test
    fun blankCaptionBecomesNull() {
        val payload = buildFilePayload("phone", listOf(ref()), now = 1, caption = "   ")
        assertNull(payload.caption)
    }

    /** 長すぎるキャプションはバイト予算で切り詰める。 */
    @Test
    fun longCaptionTruncated() {
        val payload = buildFilePayload("phone", listOf(ref()), now = 1, caption = "あ".repeat(2000))
        assertTrue(payload.caption!!.encodeToByteArray().size <= MAX_CAPTION_BYTES)
    }

    /** 添付なしでは FilePayload を組めない。 */
    @Test
    fun requiresAtLeastOneAttachment() {
        assertFailsWith<IllegalArgumentException> {
            buildFilePayload("phone", emptyList(), now = 1)
        }
    }

    /** 優先度は指定を反映する。 */
    @Test
    fun priorityIsCarried() {
        val payload = buildFilePayload("phone", listOf(ref()), now = 1, priority = Priority.HIGH)
        assertEquals(Priority.HIGH, payload.priority)
    }
}
