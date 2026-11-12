package to.sava.peranta.blob

import kotlin.test.Test
import kotlin.test.assertEquals

/** 添付の MIME を、種別を語る形へ決める [resolveAttachmentMimeType]（§4.3・§4.3.2）。 */
class AttachmentMimeTypeTest {

    private val knownExtensions = mapOf("jpg" to "image/jpeg", "pdf" to "application/pdf")

    private fun resolve(mimeType: String?, fileName: String): String =
        resolveAttachmentMimeType(mimeType, fileName) { knownExtensions[it] }

    /** 種別を語る MIME は、パラメータと前後の空白を落として小文字にそろえた形で返す。拡張子は引かない。 */
    @Test
    fun specificMimeTypeIsReturnedNormalized() {
        assertEquals("text/plain", resolve(" Text/Plain; charset=utf-8", "photo.jpg"))
    }

    /** ワイルドカードを含む MIME は返さない。拡張子から引けるならそれを、引けなければ既定の MIME を返す。 */
    @Test
    fun wildcardMimeTypeIsNeverReturned() {
        listOf("*/*", "application/*", "image/*").forEach { wildcard ->
            assertEquals("image/jpeg", resolve(wildcard, "photo.jpg"), "$wildcard / photo.jpg")
            assertEquals(DEFAULT_ATTACHMENT_MIME_TYPE, resolve(wildcard, "noext"), "$wildcard / noext")
            assertEquals(DEFAULT_ATTACHMENT_MIME_TYPE, resolve(wildcard, "data.xyz"), "$wildcard / data.xyz")
        }
    }

    /** 種別を語らない MIME（null・空・既定の MIME）も、拡張子から補う。 */
    @Test
    fun nonSpecificMimeTypeIsFilledFromExtension() {
        listOf(null, "", DEFAULT_ATTACHMENT_MIME_TYPE).forEach { mimeType ->
            assertEquals("application/pdf", resolve(mimeType, "invoice.pdf"), "$mimeType")
        }
    }
}
