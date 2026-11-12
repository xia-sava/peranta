package to.sava.peranta.blob

import to.sava.peranta.model.AttachmentKind
import kotlin.test.Test
import kotlin.test.assertEquals

class AttachmentCategoryTest {

    /** mimeType が種別を語るときはそれを第一にし、画像なら IMAGE、画像以外なら拡張子に関わらず FILE にする（§4.3）。 */
    @Test
    fun kindFollowsMimeTypeFirst() {
        assertEquals(AttachmentKind.IMAGE, attachmentKindFor("image/jpeg", "photo.jpg"))
        assertEquals(AttachmentKind.IMAGE, attachmentKindFor("image/png", "noext"))
        assertEquals(AttachmentKind.FILE, attachmentKindFor("application/pdf", "photo.jpg"))
        assertEquals(AttachmentKind.FILE, attachmentKindFor("video/mp4", "clip.mp4"))
    }

    /** mimeType が種別を語らないときは、拡張子だけで画像と判れば IMAGE、判らなければ FILE にする。 */
    @Test
    fun kindFallsBackToImageExtension() {
        val octet = "application/octet-stream"
        assertEquals(AttachmentKind.IMAGE, attachmentKindFor(octet, "photo.jpg"))
        assertEquals(AttachmentKind.IMAGE, attachmentKindFor(octet, "pic.HEIC"))
        assertEquals(AttachmentKind.FILE, attachmentKindFor(octet, "report.pdf"))
        assertEquals(AttachmentKind.FILE, attachmentKindFor(octet, "noext"))
    }

    /**
     * mimeType が表示種別を決められない型（`application/zip` 等）なら、表示種別と同じく拡張子で決める。
     * 受信側は kind でサムネイルと通知の画像を、表示種別で自動取得とラベルを決めるため、両者を一致させる。
     */
    @Test
    fun kindFollowsExtensionWhenMimeTypeHasNoCategory() {
        assertEquals(AttachmentCategory.IMAGE, attachmentCategoryFor("application/zip", "photo.jpg"))
        assertEquals(AttachmentKind.IMAGE, attachmentKindFor("application/zip", "photo.jpg"))
        assertEquals(AttachmentKind.FILE, attachmentKindFor("application/zip", "archive.zip"))
    }

    /** mimeType のトップレベル型が画像・動画・音声・テキストなら、その種別を優先する。 */
    @Test
    fun categoryFollowsMimeTopLevel() {
        assertEquals(AttachmentCategory.IMAGE, attachmentCategoryFor("image/png", "x"))
        assertEquals(AttachmentCategory.VIDEO, attachmentCategoryFor("video/mp4", "x"))
        assertEquals(AttachmentCategory.AUDIO, attachmentCategoryFor("audio/mpeg", "x"))
        assertEquals(AttachmentCategory.DOCUMENT, attachmentCategoryFor("text/plain", "x"))
        assertEquals(AttachmentCategory.DOCUMENT, attachmentCategoryFor("application/pdf", "x"))
    }

    /** mimeType が曖昧（application オクテットストリーム等）なときは拡張子で補助分類する。 */
    @Test
    fun categoryFallsBackToExtension() {
        val octet = "application/octet-stream"
        assertEquals(AttachmentCategory.DOCUMENT, attachmentCategoryFor(octet, "report.xlsx"))
        assertEquals(AttachmentCategory.VIDEO, attachmentCategoryFor(octet, "clip.mkv"))
        assertEquals(AttachmentCategory.AUDIO, attachmentCategoryFor(octet, "song.flac"))
        assertEquals(AttachmentCategory.IMAGE, attachmentCategoryFor(octet, "pic.heic"))
    }

    /** どの mimeType・拡張子にも当てはまらなければ OTHER に落とす。 */
    @Test
    fun unknownFallsBackToOther() {
        assertEquals(AttachmentCategory.OTHER, attachmentCategoryFor("application/octet-stream", "archive.zip"))
        assertEquals(AttachmentCategory.OTHER, attachmentCategoryFor("application/x-thing", "noext"))
    }
}
