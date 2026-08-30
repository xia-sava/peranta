package to.sava.peranta.toast

import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import to.sava.peranta.model.CommandPayload
import to.sava.peranta.model.CommandType
import to.sava.peranta.model.FilePayload
import to.sava.peranta.model.MessagePayload
import to.sava.peranta.model.NotificationActionDetail
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ErrorKind
import to.sava.peranta.timeline.ReceivedFile
import to.sava.peranta.timeline.ReceivedMessage
import to.sava.peranta.timeline.ReceivedNotification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertEquals(
            ReceivedNotificationToast(id = "n1", title = "認証コード", body = "123456", source = "phone ・ Bank"),
            content,
        )
    }

    /** 発信元には転送元の端末名とアプリ名を並べる（タイトルだけでは何の通知か分からないため）。 */
    @Test
    fun notificationCarriesDeviceAndAppNameAsSource() {
        val content = toastContentFor(notification(appName = "Gmail"))
        assertEquals("phone ・ Gmail", content?.source)
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

    /** 本文に URL があれば openUrl に写す。 */
    @Test
    fun notificationExtractsOpenUrlFromText() {
        val content = toastContentFor(notification(text = "詳細は https://example.com/info を見て"))
        assertEquals("https://example.com/info", content?.openUrl)
    }

    /** タイトル中の URL も抽出対象になる。 */
    @Test
    fun notificationExtractsOpenUrlFromTitle() {
        val content = toastContentFor(notification(title = "https://example.com/title", text = "本文"))
        assertEquals("https://example.com/title", content?.openUrl)
    }

    /** 複数 URL があれば先頭のみを openUrl に採る。 */
    @Test
    fun notificationExtractsFirstOpenUrlWhenMultiple() {
        val content = toastContentFor(
            notification(text = "https://a.example/ と https://b.example/ を見て"),
        )
        assertEquals("https://a.example/", content?.openUrl)
    }

    /** URL が無ければ openUrl は null のまま。 */
    @Test
    fun notificationOpenUrlNullWhenNoUrl() {
        val content = toastContentFor(notification(text = "ただのテキスト"))
        assertNull(content?.openUrl)
    }

    /** SMS は本文の URL を openUrl に写す。 */
    @Test
    fun smsExtractsOpenUrlFromText() {
        val item = ReceivedNotification(
            id = "s3",
            timestampEpochMillis = 1_000,
            payload = SmsPayload(
                id = "s3",
                from = "phone",
                to = "*",
                sentAtEpochMillis = 900,
                senderNumber = "+81900000000",
                senderName = "銀行",
                text = "確認は https://example.com/verify から",
                postedAtEpochMillis = 900,
            ),
        )
        assertEquals("https://example.com/verify", toastContentFor(item)?.openUrl)
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
            ReceivedNotificationToast(
                id = "s1",
                title = "銀行",
                body = "コードは 987654 です",
                source = "phone ・ SMS",
            ),
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
            ReceivedNotificationToast(
                id = "rf1",
                title = "ファイルを受信しました",
                body = "photo.jpg",
                source = "phone",
            ),
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

    private fun receivedMessage(text: String = "会議は 15 時からです", fromName: String? = "xia-phone") = ReceivedMessage(
        id = "m1",
        timestampEpochMillis = 1_000,
        payload = MessagePayload(
            id = "m1",
            from = "phone",
            to = "*",
            sentAtEpochMillis = 900,
            text = text,
            fromName = fromName,
        ),
    )

    /** 受信メッセージは fromName をタイトルに、本文を body に写す。 */
    @Test
    fun messageMapsFromNameAndText() {
        assertEquals(
            ReceivedNotificationToast(id = "m1", title = "xia-phone", body = "会議は 15 時からです"),
            toastContentFor(receivedMessage()),
        )
    }

    /** fromName が無ければ from（deviceId）をタイトルに使う。 */
    @Test
    fun messageFallsBackToFromWhenFromNameAbsent() {
        assertEquals("phone", toastContentFor(receivedMessage(fromName = null)).title)
    }

    /** 本文が空なら本文なしラベルにフォールバックする。 */
    @Test
    fun messageFallsBackWhenBodyBlank() {
        assertEquals("（本文なし）", toastContentFor(receivedMessage(text = "")).body)
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

    /** 発出元で完結する（画面を開かない）アクション。 */
    private val senderEffect = NotificationActionDetail(hasRemoteInput = false, opensActivity = false)

    /** 発出元で画面が開くアクション。 */
    private val opensOnSender = NotificationActionDetail(hasRemoteInput = false, opensActivity = true)

    private fun withActions(
        actions: List<String>,
        actionDetails: List<NotificationActionDetail>,
    ) = ReceivedNotification(
        id = "a1",
        timestampEpochMillis = 1_000,
        payload = notificationWithActions(actions, actionDetails),
    )

    private fun notificationWithActions(
        actions: List<String>,
        actionDetails: List<NotificationActionDetail>,
    ) = NotificationPayload(
        id = "a1",
        from = "phone",
        to = "*",
        sentAtEpochMillis = 900,
        packageName = "com.google.android.gm",
        appName = "Gmail",
        title = "差出人",
        text = "件名",
        notificationKey = "0|com.google.android.gm|1|null|10",
        postedAtEpochMillis = 900,
        actions = actions,
        actionDetails = actionDetails,
    )

    /** 発出元で完結するアクションはトーストのボタンになる。 */
    @Test
    fun senderEffectActionsBecomeToastButtons() {
        val content = toastContentFor(
            withActions(listOf("アーカイブ", "既読にする"), listOf(senderEffect, senderEffect)),
        )
        assertEquals(
            listOf(ToastAction(index = 0, label = "アーカイブ"), ToastAction(index = 1, label = "既読にする")),
            content?.actions,
        )
    }

    /** 発出元で画面が開くだけのアクションは載せない（手元で結果を確かめられないため）。 */
    @Test
    fun opensOnSenderActionIsNotOffered() {
        val content = toastContentFor(
            withActions(listOf("アーカイブ", "返信"), listOf(senderEffect, opensOnSender)),
        )
        assertEquals(listOf(ToastAction(index = 0, label = "アーカイブ")), content?.actions)
    }

    /** インライン返信（RemoteInput つき）のアクションは、入力が要る印を付けて載せる。 */
    @Test
    fun replyActionIsOfferedAsInputAction() {
        val reply = NotificationActionDetail(hasRemoteInput = true, opensActivity = false)
        val content = toastContentFor(withActions(listOf("返信"), listOf(reply)))
        assertEquals(
            listOf(ToastAction(index = 0, label = "返信", needsInput = true)),
            content?.actions,
        )
    }

    /** 発出元で完結するアクションには入力が要る印を付けない。 */
    @Test
    fun senderEffectActionDoesNotNeedInput() {
        val content = toastContentFor(withActions(listOf("アーカイブ"), listOf(senderEffect)))
        assertEquals(listOf(false), content?.actions?.map { it.needsInput })
    }

    /** 分類する材料が無いアクション（旧送信元から届いたもの）は載せない。 */
    @Test
    fun actionWithoutDetailIsNotOffered() {
        val content = toastContentFor(withActions(listOf("アーカイブ"), emptyList()))
        assertEquals(emptyList(), content?.actions)
    }

    /** 名前が空白だけのアクションは押しても何のボタンか分からないため載せない。 */
    @Test
    fun blankLabelActionIsNotOffered() {
        val content = toastContentFor(withActions(listOf("  "), listOf(senderEffect)))
        assertEquals(emptyList(), content?.actions)
    }

    /** 載せないアクションを飛ばしても、位置は元通知での並びのまま保つ（発火はこの位置を指すため）。 */
    @Test
    fun offeredActionKeepsOriginalIndex() {
        val content = toastContentFor(
            withActions(listOf("返信", "アーカイブ"), listOf(opensOnSender, senderEffect)),
        )
        assertEquals(listOf(ToastAction(index = 1, label = "アーカイブ")), content?.actions)
    }

    /** アクションの並びが変わっていなければ、表示していたボタンはそのまま発火してよい。 */
    @Test
    fun actionStillOfferedWhenLabelUnchanged() {
        val payload = notificationWithActions(listOf("アーカイブ", "既読にする"), listOf(senderEffect, senderEffect))
        assertTrue(isActionStillOffered(payload, ToastAction(index = 1, label = "既読にする")))
    }

    /** 元通知が差し替わって同じ位置の名前が変わっていたら、別の操作になるので発火しない。 */
    @Test
    fun actionNotOfferedWhenLabelChanged() {
        val payload = notificationWithActions(listOf("元に戻す"), listOf(senderEffect))
        assertFalse(isActionStillOffered(payload, ToastAction(index = 0, label = "アーカイブ")))
    }

    /** アクションごと消えていたら発火しない。 */
    @Test
    fun actionNotOfferedWhenGone() {
        val payload = notificationWithActions(emptyList(), emptyList())
        assertFalse(isActionStillOffered(payload, ToastAction(index = 0, label = "アーカイブ")))
    }
}
