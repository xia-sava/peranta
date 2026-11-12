package to.sava.peranta.send

import io.ktor.utils.io.ByteReadChannel
import to.sava.peranta.blob.DEFAULT_ATTACHMENT_MIME_TYPE
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.BlobEnc
import to.sava.peranta.model.MAX_CAPTION_BYTES
import to.sava.peranta.model.Priority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileForwardingTest {

    /** 拡張子から MIME を引く口へ渡された拡張子。 */
    private val lookedUpExtensions = mutableListOf<String>()

    /** 拡張子から MIME を引く口の対応表。 */
    private val extensionTable = mapOf("jpg" to "image/jpeg", "pdf" to "application/pdf")

    private val emptySource: suspend () -> ByteReadChannel = { ByteReadChannel(ByteArray(0)) }

    private fun uploadRequest(
        fileName: String,
        mimeType: String?,
        mimeTypeForExtension: (String) -> String? = { extension ->
            lookedUpExtensions.add(extension)
            extensionTable[extension]
        },
    ) = buildFileUploadRequest(
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = 42,
        mimeTypeForExtension = mimeTypeForExtension,
        openSource = emptySource,
    )

    /**
     * 種別を語る MIME は、前後の空白とパラメータを落として小文字にそろえた形で載せ、拡張子からは引かない。
     */
    @Test
    fun specificMimeTypeIsNormalizedWithoutLookup() {
        listOf(
            "application/pdf" to "application/pdf",
            "IMAGE/JPEG" to "image/jpeg",
            " image/png " to "image/png",
            "\timage/png\n" to "image/png",
            "text/plain; charset=utf-8" to "text/plain",
            " Text/Plain ; charset=UTF-8" to "text/plain",
        ).forEach { (given, expected) ->
            assertEquals(expected, uploadRequest("photo.jpg", given).mimeType, "mimeType=$given")
        }
        assertTrue(lookedUpExtensions.isEmpty())
    }

    /** kind は正規化した MIME で決める（大文字・前後の空白・パラメータがあっても画像と判る）。 */
    @Test
    fun kindFollowsNormalizedMimeType() {
        listOf("IMAGE/PNG", " image/png", "image/png; q=1").forEach { mimeType ->
            assertEquals(AttachmentKind.IMAGE, uploadRequest("noext", mimeType).kind, "mimeType=$mimeType")
        }
    }

    /** MIME が null・空・空白なら、拡張子から引いた MIME で補う（§4.3）。 */
    @Test
    fun missingMimeTypeIsFilledFromExtension() {
        listOf(null, "", "  ").forEach { mimeType ->
            assertEquals("image/jpeg", uploadRequest("photo.jpg", mimeType).mimeType, "mimeType=$mimeType")
        }
    }

    /**
     * 具体的な種別を語らない MIME も、拡張子から引いた MIME で補う（§4.3）。対象は、type か subtype が
     * ワイルドカードのもの、`type/subtype` の形でないもの、途中に空白や制御文字を含むもの、`application/octet-stream`。
     */
    @Test
    fun nonSpecificMimeTypeIsFilledFromExtension() {
        listOf(
            "*/*",
            "image/*",
            "image",
            "image/",
            "/jpeg",
            "image/png/x",
            "image /png",
            "image/p\u0000ng",
            "application/octet-stream",
            "APPLICATION/OCTET-STREAM",
        ).forEach { mimeType ->
            assertEquals("image/jpeg", uploadRequest("photo.jpg", mimeType).mimeType, "mimeType=$mimeType")
        }
    }

    /**
     * type が具体的で subtype がワイルドカードの MIME も語らないものとして扱い、type の情報は残さない。
     * 拡張子から引ければその MIME に、引けなければ既定の MIME になり、どちらも画像でなければ kind は FILE。
     */
    @Test
    fun wildcardSubtypeIsNotKept() {
        data class Case(val fileName: String, val mimeType: String, val kind: AttachmentKind)
        listOf(
            Case("noext", DEFAULT_ATTACHMENT_MIME_TYPE, AttachmentKind.FILE),
            Case("archive.xyz", DEFAULT_ATTACHMENT_MIME_TYPE, AttachmentKind.FILE),
            Case("report.pdf", "application/pdf", AttachmentKind.FILE),
        ).forEach { case ->
            val request = uploadRequest(case.fileName, "image/*")
            assertEquals(case.mimeType, request.mimeType, "fileName=${case.fileName}")
            assertEquals(case.kind, request.kind, "fileName=${case.fileName}")
        }
    }

    /** 拡張子は小文字・ドットなしで引く。 */
    @Test
    fun extensionIsLookedUpInLowerCaseWithoutDot() {
        uploadRequest("PHOTO.JPG", null)
        assertEquals(listOf("jpg"), lookedUpExtensions)
    }

    /**
     * 拡張子は、保存時に無害化した名前で決める。先頭のドットだけの名前（`.jpg`）は拡張子なし、
     * 末尾のドットは落とし（`photo.jpg.` は jpg）、ドットが複数あれば最後の区切りを使う（`a.tar.gz` は gz）。
     */
    @Test
    fun extensionFollowsNormalizedFileName() {
        data class Case(val fileName: String, val lookedUp: List<String>, val mimeType: String, val kind: AttachmentKind)
        listOf(
            Case(".jpg", emptyList(), DEFAULT_ATTACHMENT_MIME_TYPE, AttachmentKind.FILE),
            Case("photo.jpg.", listOf("jpg"), "image/jpeg", AttachmentKind.IMAGE),
            Case("a.tar.gz", listOf("gz"), DEFAULT_ATTACHMENT_MIME_TYPE, AttachmentKind.FILE),
        ).forEach { case ->
            lookedUpExtensions.clear()
            val request = uploadRequest(case.fileName, null)
            assertEquals(case.lookedUp, lookedUpExtensions, "fileName=${case.fileName}")
            assertEquals(case.mimeType, request.mimeType, "fileName=${case.fileName}")
            assertEquals(case.kind, request.kind, "fileName=${case.fileName}")
        }
    }

    /** 拡張子から引いても判らなければ、既定の MIME（`application/octet-stream`）にする。 */
    @Test
    fun unknownExtensionFallsBackToDefault() {
        assertEquals(DEFAULT_ATTACHMENT_MIME_TYPE, uploadRequest("archive.xyz", null).mimeType)
        assertEquals(listOf("xyz"), lookedUpExtensions)
    }

    /** 拡張子が無ければ引かずに既定の MIME にする。 */
    @Test
    fun missingExtensionFallsBackToDefaultWithoutLookup() {
        assertEquals(DEFAULT_ATTACHMENT_MIME_TYPE, uploadRequest("noext", null).mimeType)
        assertTrue(lookedUpExtensions.isEmpty())
    }

    /** 拡張子から引いた MIME も種別を語らなければ、既定の MIME にする。 */
    @Test
    fun nonSpecificLookupResultFallsBackToDefault() {
        listOf("*/*", "image/*", "image", "  ", "application/octet-stream").forEach { looked ->
            val request = uploadRequest("archive.xyz", null, mimeTypeForExtension = { looked })
            assertEquals(DEFAULT_ATTACHMENT_MIME_TYPE, request.mimeType, "looked=$looked")
        }
    }

    /** 拡張子から引いた MIME も、前後の空白とパラメータを落として小文字にそろえた形で載せる。 */
    @Test
    fun lookupResultIsNormalized() {
        val request = uploadRequest("photo.jpg", null, mimeTypeForExtension = { " IMAGE/JPEG; q=1" })
        assertEquals("image/jpeg", request.mimeType)
        assertEquals(AttachmentKind.IMAGE, request.kind)
    }

    /** MIME が画像なら、拡張子が無くても kind は IMAGE になる。 */
    @Test
    fun kindIsImageForImageMimeType() {
        assertEquals(AttachmentKind.IMAGE, uploadRequest("noext", "image/png").kind)
    }

    /**
     * MIME が表示種別を決められない型（`application/zip`）なら、MIME はそのまま載せ、kind は拡張子で決まる。
     * 受信側が mimeType と拡張子で決める表示種別と一致させるため。
     */
    @Test
    fun mimeTypeWithoutCategoryIsKeptAndKindFollowsExtension() {
        val request = uploadRequest("photo.jpg", "application/zip")
        assertEquals("application/zip", request.mimeType)
        assertEquals(AttachmentKind.IMAGE, request.kind)
        assertTrue(lookedUpExtensions.isEmpty())
    }

    /**
     * MIME が判らず拡張子が画像なら、kind は IMAGE になる。拡張子から MIME を引けたときはその MIME で、
     * 引けなかったときも既定の MIME と拡張子の分類で画像と判る。
     */
    @Test
    fun kindIsImageWhenMimeTypeIsUnknownAndExtensionIsImage() {
        val filled = uploadRequest("photo.jpg", null)
        assertEquals("image/jpeg", filled.mimeType)
        assertEquals(AttachmentKind.IMAGE, filled.kind)

        val notFilled = uploadRequest("pic.heic", null)
        assertEquals(DEFAULT_ATTACHMENT_MIME_TYPE, notFilled.mimeType)
        assertEquals(AttachmentKind.IMAGE, notFilled.kind)
    }

    /** MIME が画像以外の種別を語るなら、拡張子が画像でも kind は FILE になる（MIME を第一にする）。 */
    @Test
    fun kindIsFileWhenMimeTypeIsNonImageEvenIfExtensionIsImage() {
        val request = uploadRequest("photo.jpg", "application/pdf")
        assertEquals("application/pdf", request.mimeType)
        assertEquals(AttachmentKind.FILE, request.kind)
    }

    /** ファイル名・長さ・`openSource` は渡したものをそのまま載せる。 */
    @Test
    fun carriesFileNameSizeAndSource() {
        val request = uploadRequest("旅行 写真.jpg", "image/jpeg")
        assertEquals("旅行 写真.jpg", request.fileName)
        assertEquals(42L, request.sizeBytes)
        assertSame(emptySource, request.openSource)
    }

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
