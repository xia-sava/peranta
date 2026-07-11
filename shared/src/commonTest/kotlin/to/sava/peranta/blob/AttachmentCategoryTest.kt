package to.sava.peranta.blob

import to.sava.peranta.model.AttachmentKind
import kotlin.test.Test
import kotlin.test.assertEquals

class AttachmentCategoryTest {

    /** image で始まる mimeType は IMAGE、それ以外は FILE に分類する（§4.3）。 */
    @Test
    fun kindFollowsImageMimePrefix() {
        assertEquals(AttachmentKind.IMAGE, attachmentKindForMimeType("image/jpeg"))
        assertEquals(AttachmentKind.IMAGE, attachmentKindForMimeType("image/png"))
        assertEquals(AttachmentKind.FILE, attachmentKindForMimeType("application/pdf"))
        assertEquals(AttachmentKind.FILE, attachmentKindForMimeType("video/mp4"))
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
