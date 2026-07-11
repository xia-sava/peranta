package to.sava.peranta.toast

import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import to.sava.peranta.model.CommandPayload
import to.sava.peranta.model.CommandType
import to.sava.peranta.model.FilePayload
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ErrorKind
import to.sava.peranta.timeline.ReceivedFile
import to.sava.peranta.timeline.ReceivedNotification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ToastContentTest {

    private fun notification(
        title: String = "認証コード",
        text: String = "123456",
        appName: String = "Bank",
    ) = ReceivedNotification(
        id = "n1",
        timestampEpochMillis = 1_000,
        payload = NotificationPayload(
            id = "n1",
            from = "phone",
            to = "*",
            sentAtEpochMillis = 900,
            packageName = "com.example.bank",
            appName = appName,
            title = title,
            text = text,
            notificationKey = "0|com.example.bank|1|null|10",
            postedAtEpochMillis = 900,
        ),
    )

    /** 通知はタイトルと本文をそのままトースト内容に写す。id は保つ。 */
    @Test
    fun notificationMapsTitleAndBody() {
        val content = toastContentFor(notification())
        assertEquals(ReceivedNotificationToast(id = "n1", title = "認証コード", body = "123456"), content)
    }

    /** タイトルが空なら appName にフォールバックする。 */
    @Test
    fun notificationFallsBackToAppNameWhenTitleBlank() {
        val content = toastContentFor(notification(title = "  "))
        assertEquals("Bank", content?.title)
    }

    /** タイトルも appName も空なら Peranta にフォールバックする。 */
    @Test
    fun notificationFallsBackToPerantaWhenTitleAndAppNameBlank() {
        val content = toastContentFor(notification(title = "", appName = ""))
        assertEquals("Peranta", content?.title)
    }

    /** 本文が空なら本文なしラベルにフォールバックする。 */
    @Test
    fun notificationFallsBackWhenBodyBlank() {
        val content = toastContentFor(notification(text = ""))
        assertEquals("（本文なし）", content?.body)
    }

    /** SMS は送信者表示名をタイトルに、本文を body に写す。 */
    @Test
    fun smsUsesSenderNameAsTitle() {
        val item = ReceivedNotification(
            id = "s1",
            timestampEpochMillis = 1_000,
            payload = SmsPayload(
                id = "s1",
                from = "phone",
                to = "*",
                sentAtEpochMillis = 900,
                senderNumber = "+81900000000",
                senderName = "銀行",
                text = "コードは 987654 です",
                postedAtEpochMillis = 900,
            ),
        )
        assertEquals(
            ReceivedNotificationToast(id = "s1", title = "銀行", body = "コードは 987654 です"),
            toastContentFor(item),
        )
    }

    /** 送信者名が無い SMS は電話番号をタイトルに使う。 */
    @Test
    fun smsFallsBackToSenderNumber() {
        val item = ReceivedNotification(
            id = "s2",
            timestampEpochMillis = 1_000,
            payload = SmsPayload(
                id = "s2",
                from = "phone",
                to = "*",
                sentAtEpochMillis = 900,
                senderNumber = "+81900000000",
                senderName = null,
                text = "本文",
                postedAtEpochMillis = 900,
            ),
        )
        assertEquals("+81900000000", toastContentFor(item)?.title)
    }

    /** 表示対象外の payload（command 等）は null を返す。 */
    @Test
    fun nonDisplayablePayloadReturnsNull() {
        val item = ReceivedNotification(
            id = "c1",
            timestampEpochMillis = 1_000,
            payload = CommandPayload(
                id = "c1",
                from = "desk",
                to = "phone",
                sentAtEpochMillis = 900,
                command = CommandType.DISMISS,
            ),
        )
        assertNull(toastContentFor(item))
    }

    private fun attachment(fileName: String) = AttachmentRef(
        blobId = "blob-$fileName",
        url = "https://ntfy.example/file/blob",
        fileName = fileName,
        mimeType = "image/jpeg",
        sizeBytes = 2048,
        kind = AttachmentKind.IMAGE,
        enc = BlobEnc(keyId = "k1", saltBase64 = "c2FsdA==", chunkSize = 1024, totalChunks = 2),
    )

    private fun receivedFile(vararg fileNames: String) = ReceivedFile(
        id = "rf1",
        timestampEpochMillis = 1_000,
        payload = FilePayload(
            id = "rf1",
            from = "phone",
            to = "*",
            sentAtEpochMillis = 900,
            attachments = fileNames.map { attachment(it) },
            postedAtEpochMillis = 900,
        ),
    )

    /** 単一ファイルの受信は見出しとファイル名のトーストになる。 */
    @Test
    fun receivedFileShowsFileName() {
        assertEquals(
            ReceivedNotificationToast(id = "rf1", title = "ファイルを受信しました", body = "photo.jpg"),
            toastContentFor(receivedFile("photo.jpg")),
        )
    }

    /** 複数ファイルは先頭名と残り件数を本文に載せる。 */
    @Test
    fun receivedMultipleFilesSummarizesCount() {
        assertEquals("photo.jpg ほか 2 件", toastContentFor(receivedFile("photo.jpg", "a.png", "b.pdf")).body)
    }

    /** ファイル名が空なら代替表示にフォールバックする。 */
    @Test
    fun receivedFileFallsBackWhenNameBlank() {
        assertEquals("ファイル", toastContentFor(receivedFile("")).body)
    }

    /** エラーアイテムは受信エラー見出しとメッセージ本文のトーストになる。 */
    @Test
    fun errorMapsToErrorToast() {
        val item = ErrorItem(
            id = "e1",
            timestampEpochMillis = 1_000,
            message = "通知の復号に失敗しました",
            kind = ErrorKind.DECRYPTION,
        )
        assertEquals(
            ReceivedNotificationToast(id = "e1", title = "Peranta 受信エラー", body = "通知の復号に失敗しました"),
            toastContentFor(item),
        )
    }

    /** メッセージが空のエラーは本文なしラベルにフォールバックする。 */
    @Test
    fun errorWithBlankMessageFallsBack() {
        val item = ErrorItem(id = "e2", timestampEpochMillis = 1_000, message = "", kind = ErrorKind.OTHER)
        assertEquals("（本文なし）", toastContentFor(item).body)
    }
}
