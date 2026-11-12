package to.sava.peranta.blob

import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 画像デコードの上限（§4.3）。画素数の上限は Android の縮小デコードと Desktop の等倍デコードが同じ値を見る。
 * サムネイルにするかの判定（[shouldDecodeThumbnail]）も両者で共有する。
 */
class ImageDecodeLimitsTest {

    private fun ref(kind: AttachmentKind, mimeType: String = "image/jpeg") = AttachmentRef(
        blobId = "blob-1",
        url = "https://ntfy.example/file/blob-1",
        fileName = "photo.jpg",
        mimeType = mimeType,
        sizeBytes = 2048,
        kind = kind,
        enc = BlobEnc(keyId = "k1", saltBase64 = "c2FsdA==", chunkSize = 1024, totalChunks = 2),
    )

    /** kind が画像で、上限以内（ちょうどを含む）のファイルはサムネイルにする。 */
    @Test
    fun imageKindWithinLimitIsDecoded() {
        assertTrue(shouldDecodeThumbnail(ref(AttachmentKind.IMAGE), fileSizeBytes = 2048))
        assertTrue(shouldDecodeThumbnail(ref(AttachmentKind.IMAGE), fileSizeBytes = MAX_THUMBNAIL_DECODE_BYTES))
    }

    /** 画像かどうかは kind で決まる。kind が画像なら、mimeType が画像を名乗らなくてもサムネイルにする。 */
    @Test
    fun imageKindIsDecodedRegardlessOfMimeType() {
        assertTrue(shouldDecodeThumbnail(ref(AttachmentKind.IMAGE, mimeType = "application/octet-stream"), 2048))
    }

    /** mimeType が画像でも、kind が FILE の添付はサムネイルにしない。 */
    @Test
    fun fileKindWithImageMimeIsNotDecoded() {
        assertFalse(shouldDecodeThumbnail(ref(AttachmentKind.FILE, mimeType = "image/jpeg"), 2048))
    }

    /** 全文添付（kind=TEXT）はサムネイルにしない。 */
    @Test
    fun textKindIsNotDecoded() {
        assertFalse(shouldDecodeThumbnail(ref(AttachmentKind.TEXT, mimeType = "text/plain"), 2048))
    }

    /** 上限を 1 バイトでも超えるファイルは、画像でもサムネイルにしない。 */
    @Test
    fun imageOverLimitIsNotDecoded() {
        assertFalse(shouldDecodeThumbnail(ref(AttachmentKind.IMAGE), fileSizeBytes = MAX_THUMBNAIL_DECODE_BYTES + 1))
    }

    /** 実際に撮られた写真の画素数は通す（数千万画素）。 */
    @Test
    fun realPhotoDimensionsPass() {
        assertFalse(exceedsDecodedPixelLimit(4000, 3000))
        assertFalse(exceedsDecodedPixelLimit(8000, 6000))
    }

    /** 圧縮率だけを利用した巨大な寸法は拒否する（30000×30000 で 9 億画素）。 */
    @Test
    fun decompressionBombDimensionsAreRejected() {
        assertTrue(exceedsDecodedPixelLimit(30_000, 30_000))
    }

    /** 極端な縦横比でも画素数の総量で判定する（片方が小さくても総量が上限を超えれば拒否）。 */
    @Test
    fun extremeAspectRatioIsJudgedByTotalPixels() {
        assertTrue(exceedsDecodedPixelLimit(1_000_000, 100))
        assertFalse(exceedsDecodedPixelLimit(1_000_000, 60))
    }

    /** 上限ちょうどは通し、1 画素超えたら拒否する。 */
    @Test
    fun theLimitItselfPasses() {
        assertFalse(exceedsDecodedPixelLimit(8192, 8192))
        assertTrue(exceedsDecodedPixelLimit(8193, 8192))
    }

    /** 寸法を読めなかった場合（0 以下）はデコードへ進ませない。 */
    @Test
    fun unreadableDimensionsAreRejected() {
        assertTrue(exceedsDecodedPixelLimit(0, 100))
        assertTrue(exceedsDecodedPixelLimit(100, 0))
        assertTrue(exceedsDecodedPixelLimit(-1, -1))
    }
}
