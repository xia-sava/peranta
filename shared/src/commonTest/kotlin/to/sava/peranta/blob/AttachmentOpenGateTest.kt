package to.sava.peranta.blob

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 受信した添付を OS へ渡してよいかの判定（§4.3.2）。 */
class AttachmentOpenGateTest {

    /** 日常的に届く表示だけの種別は確認なしで開ける（確認が読み飛ばされる状態を作らない）。 */
    @Test
    fun knownViewableTypesOpenWithoutConfirmation() {
        listOf(
            "image/jpeg" to "photo.jpg",
            "image/png" to "shot.png",
            "image/heic" to "IMG_0001.heic",
            "application/pdf" to "invoice.pdf",
            "text/plain" to "notes.txt",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "report.docx",
            "video/mp4" to "clip.mp4",
            "audio/mpeg" to "song.mp3",
        ).forEach { (mimeType, fileName) ->
            assertEquals(
                AttachmentOpenDecision.OPEN,
                attachmentOpenDecision(mimeType, fileName),
                "$mimeType / $fileName",
            )
        }
    }

    /** 開くだけで実行につながる拡張子は、mimeType が何を名乗っていても渡さない。 */
    @Test
    fun executableExtensionsAreRefused() {
        listOf(
            "invoice.exe", "setup.bat", "run.cmd", "x.com", "x.pif", "saver.scr",
            "link.lnk", "page.hta", "s.js", "s.vbs", "installer.msi", "script.ps1",
            "keys.reg", "app.apk", "lib.dll", "tool.jar", "site.url",
        ).forEach { fileName ->
            assertEquals(
                AttachmentOpenDecision.REFUSE,
                attachmentOpenDecision("application/octet-stream", fileName),
                fileName,
            )
        }
    }

    /** 画像を名乗る実行ファイルは弾く。拡張子が OS の扱いを決めるため、拡張子を厳しく見る。 */
    @Test
    fun executableDisguisedAsImageIsRefused() {
        assertEquals(AttachmentOpenDecision.REFUSE, attachmentOpenDecision("image/png", "invoice.pdf.exe"))
        assertEquals(AttachmentOpenDecision.REFUSE, attachmentOpenDecision("image/jpeg", "photo.scr"))
        assertEquals(AttachmentOpenDecision.REFUSE, attachmentOpenDecision("text/plain", "readme.lnk"))
    }

    /** 末尾のドット・空白は Windows が落とすため、落とした後の拡張子で判定する。 */
    @Test
    fun trailingDotsDoNotHideTheExtension() {
        assertEquals(AttachmentOpenDecision.REFUSE, attachmentOpenDecision("image/png", "invoice.exe."))
        assertEquals(AttachmentOpenDecision.REFUSE, attachmentOpenDecision("image/png", "invoice.exe "))
    }

    /** 大文字の拡張子も同じに扱う。 */
    @Test
    fun extensionMatchingIsCaseInsensitive() {
        assertEquals(AttachmentOpenDecision.REFUSE, attachmentOpenDecision("image/png", "INVOICE.EXE"))
        assertEquals(AttachmentOpenDecision.OPEN, attachmentOpenDecision("image/png", "PHOTO.PNG"))
    }

    /** パッケージインストーラへ直行する mimeType は拡張子に関わらず渡さない。 */
    @Test
    fun androidPackageMimeIsRefused() {
        assertEquals(
            AttachmentOpenDecision.REFUSE,
            attachmentOpenDecision("application/vnd.android.package-archive", "photo.png"),
        )
        assertEquals(
            AttachmentOpenDecision.REFUSE,
            attachmentOpenDecision("application/vnd.android.package-archive; charset=utf-8", "photo.png"),
        )
    }

    /** mimeType と拡張子が双方とも種別を語り、食い違うものは渡さない。 */
    @Test
    fun mimeAndExtensionMismatchIsRefused() {
        assertTrue(mimeAndExtensionConflict("image/png", "report.pdf"))
        assertEquals(AttachmentOpenDecision.REFUSE, attachmentOpenDecision("image/png", "report.pdf"))
        assertEquals(AttachmentOpenDecision.REFUSE, attachmentOpenDecision("audio/mpeg", "clip.mp4"))
    }

    /** 片方が中身を語らないときは食い違いとしない（`application/octet-stream`・拡張子なし）。 */
    @Test
    fun silentMimeOrExtensionIsNotAMismatch() {
        assertFalse(mimeAndExtensionConflict("application/octet-stream", "photo.png"))
        assertFalse(mimeAndExtensionConflict("image/png", "photo"))
        assertEquals(AttachmentOpenDecision.OPEN, attachmentOpenDecision("application/octet-stream", "photo.png"))
    }

    /** 素性を確かめられない種別は確認を挟んでから渡す。 */
    @Test
    fun unknownTypesNeedConfirmation() {
        assertEquals(AttachmentOpenDecision.CONFIRM, attachmentOpenDecision("application/zip", "archive.zip"))
        assertEquals(AttachmentOpenDecision.CONFIRM, attachmentOpenDecision("application/octet-stream", "data.bin"))
        assertEquals(AttachmentOpenDecision.CONFIRM, attachmentOpenDecision("application/octet-stream", "noext"))
    }

    /** マクロを持てる旧形式のオフィス文書は確認を挟む（新形式はマクロを持てないため確認なし）。 */
    @Test
    fun legacyOfficeDocumentsNeedConfirmation() {
        assertEquals(AttachmentOpenDecision.CONFIRM, attachmentOpenDecision("application/msword", "old.doc"))
        assertEquals(AttachmentOpenDecision.CONFIRM, attachmentOpenDecision("application/vnd.ms-excel", "old.xls"))
    }

    /** パスセパレータを含むファイル名でも、最後のセグメントの拡張子で判定する。 */
    @Test
    fun pathSeparatorsDoNotHideTheExtension() {
        assertEquals(AttachmentOpenDecision.REFUSE, attachmentOpenDecision("image/png", "../../evil.exe"))
        assertEquals(AttachmentOpenDecision.OPEN, attachmentOpenDecision("image/png", "dir/photo.png"))
    }
}
