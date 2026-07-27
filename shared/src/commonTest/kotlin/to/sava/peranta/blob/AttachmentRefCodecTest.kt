package to.sava.peranta.blob

import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import kotlin.test.Test
import kotlin.test.assertEquals

class AttachmentRefCodecTest {

    /** サービス起動 Intent へ載せる添付参照は、JSON へ直列化して復元しても同一に戻る（§4.3）。 */
    @Test
    fun roundTripsThroughJson() {
        val ref = AttachmentRef(
            blobId = "01234567-89ab-4cde-8fed-0123456789ab",
            url = "https://peranta.example.com/file/abcdef",
            fileName = "レポート.pdf",
            mimeType = "application/pdf",
            sizeBytes = 12_345_678,
            kind = AttachmentKind.FILE,
            blobExpiresAtEpochMillis = 1_800_000_000_000,
            enc = BlobEnc(keyId = "3", saltBase64 = "AAAAAAAAAAAAAAAAAAAAAA==", chunkSize = 1_048_576, totalChunks = 12),
        )
        assertEquals(ref, decodeAttachmentRef(encodeAttachmentRef(ref)))
    }
}
