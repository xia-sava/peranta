package to.sava.peranta.receive

import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import to.sava.peranta.model.CommandPayload
import to.sava.peranta.model.CommandType
import to.sava.peranta.model.FilePayload
import to.sava.peranta.model.MessagePayload
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.NotificationVisibility
import to.sava.peranta.model.Priority
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.model.SwipeBehavior
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ErrorKind
import to.sava.peranta.timeline.ReceivedFile
import to.sava.peranta.timeline.ReceivedMessage
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.SentNotification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationDisplayTest {

    private fun received(payload: to.sava.peranta.model.Payload) = ReceivedNotification(
        id = payload.id,
        timestampEpochMillis = 1_000L,
        payload = payload,
        expiresAtEpochMillis = null,
    )

    private fun notification(
        title: String = "Verification code",
        text: String = "123456",
        appName: String = "Bank",
        priority: Priority = Priority.HIGH,
        expiresAt: Long? = 5_000L,
        visibility: NotificationVisibility? = null,
        swipeBehavior: SwipeBehavior? = null,
    ) = NotificationPayload(
        id = "n1",
        from = "phone",
        to = "*",
        sentAtEpochMillis = 900L,
        packageName = "com.example.bank",
        appName = appName,
        title = title,
        text = text,
        notificationKey = "0|com.example.bank|1|null|10",
        postedAtEpochMillis = 900L,
        expiresAtEpochMillis = expiresAt,
        priority = priority,
        visibility = visibility,
        swipeBehavior = swipeBehavior,
    )

    /** 通知 payload はタイトル・本文・優先度・失効時刻を表示内容へ写す。 */
    @Test
    fun notificationMapsToDisplay() {
        val display = displayFor(received(notification()))!!
        assertEquals("n1", display.id)
        assertEquals("Verification code", display.title)
        assertEquals("123456", display.body)
        assertEquals(Priority.HIGH, display.priority)
        assertEquals(5_000L, display.expiresAtEpochMillis)
    }

    /** タイトルが空なら appName、それも空なら固定値へフォールバックする。 */
    @Test
    fun blankTitleFallsBackToAppNameThenConstant() {
        assertEquals("Bank", displayFor(received(notification(title = "")))!!.title)
        assertEquals("Peranta", displayFor(received(notification(title = "", appName = "")))!!.title)
    }

    /** 発信元には転送元の端末名とアプリ名を並べる（タイトルがアプリ名とは限らないため）。 */
    @Test
    fun notificationSourceJoinsDeviceAndAppName() {
        assertEquals("phone ・ Bank", displayFor(received(notification()))!!.source)
    }

    /** アプリ名が空なら発信元は端末名だけになる。 */
    @Test
    fun notificationSourceOmitsBlankAppName() {
        assertEquals("phone", displayFor(received(notification(appName = "")))!!.source)
    }

    /** 本文が空なら固定の代替本文になる。 */
    @Test
    fun blankBodyFallsBackToConstant() {
        assertEquals("（本文なし）", displayFor(received(notification(text = "")))!!.body)
    }

    /** 本文に URL があれば openUrl に写す。 */
    @Test
    fun notificationExtractsOpenUrlFromText() {
        val display = displayFor(received(notification(text = "詳細は https://example.com/info を見て")))!!
        assertEquals("https://example.com/info", display.openUrl)
    }

    /** 複数 URL があれば先頭のみを openUrl に採る。 */
    @Test
    fun notificationExtractsFirstOpenUrlWhenMultiple() {
        val display = displayFor(
            received(notification(text = "https://a.example/ と https://b.example/ を見て")),
        )!!
        assertEquals("https://a.example/", display.openUrl)
    }

    /** URL が無ければ openUrl は null のまま。 */
    @Test
    fun notificationOpenUrlNullWhenNoUrl() {
        val display = displayFor(received(notification(text = "ただのテキスト")))!!
        assertNull(display.openUrl)
    }

    /** 元通知のキーは表示へ写す。「送信元の通知を消す」アクションの対象になる（§3.4）。 */
    @Test
    fun notificationCarriesNotificationKey() {
        val display = displayFor(received(notification()))!!
        assertEquals("0|com.example.bank|1|null|10", display.notificationKey)
    }

    /** 対応づいていない SMS は元通知を指せないため notificationKey は null。 */
    @Test
    fun smsWithoutMatchedNotificationHasNoNotificationKey() {
        val sms = SmsPayload(
            id = "s4",
            from = "phone",
            to = "*",
            sentAtEpochMillis = 900L,
            senderNumber = "+81900000000",
            text = "コードは 123456 です",
            postedAtEpochMillis = 900L,
        )
        assertNull(displayFor(received(sms))!!.notificationKey)
    }

    /** SMS アプリの通知と対応づいた SMS は、その notificationKey を表示へ写す（§3.1）。 */
    @Test
    fun matchedSmsCarriesNotificationKey() {
        val sms = SmsPayload(
            id = "s5",
            from = "phone",
            to = "*",
            sentAtEpochMillis = 900L,
            senderNumber = "+81900000000",
            text = "コードは 123456 です",
            postedAtEpochMillis = 900L,
            notificationKey = "0|com.android.messaging|7|null|10",
        )
        assertEquals("0|com.android.messaging|7|null|10", displayFor(received(sms))!!.notificationKey)
    }

    /** 払いのけの扱いは送信端末の指示をそのまま写す（§3.3）。 */
    @Test
    fun notificationCarriesSwipeBehavior() {
        assertEquals(
            SwipeBehavior.DISMISS_SOURCE,
            displayFor(received(notification(swipeBehavior = SwipeBehavior.DISMISS_SOURCE)))!!.swipeBehavior,
        )
    }

    /** 指示を運ばない（旧バージョン由来の）通知は、払いのけで元通知を消さない既定になる。 */
    @Test
    fun notificationWithoutSwipeBehaviorDefaultsToLocalOnly() {
        assertEquals(SwipeBehavior.LOCAL_ONLY, displayFor(received(notification()))!!.swipeBehavior)
    }

    /** SMS payload は送信者名（無ければ番号）をタイトルにする。 */
    @Test
    fun smsMapsSenderToTitle() {
        val sms = SmsPayload(
            id = "s1",
            from = "phone",
            to = "*",
            sentAtEpochMillis = 900L,
            senderNumber = "+81900000000",
            senderName = "銀行",
            text = "コードは 999999 です",
            postedAtEpochMillis = 900L,
            expiresAtEpochMillis = 6_000L,
            priority = Priority.HIGH,
        )
        val display = displayFor(received(sms))!!
        assertEquals("銀行", display.title)
        assertEquals("コードは 999999 です", display.body)
        assertEquals(Priority.HIGH, display.priority)
        assertEquals("phone ・ SMS", display.source)
    }

    /** SMS は本文の URL を openUrl に写す。 */
    @Test
    fun smsExtractsOpenUrlFromText() {
        val sms = SmsPayload(
            id = "s3",
            from = "phone",
            to = "*",
            sentAtEpochMillis = 900L,
            senderNumber = "+81900000000",
            senderName = "銀行",
            text = "確認は https://example.com/verify から",
            postedAtEpochMillis = 900L,
        )
        assertEquals("https://example.com/verify", displayFor(received(sms))!!.openUrl)
    }

    /** 送信者名が無い SMS は番号をタイトルにする。 */
    @Test
    fun smsWithoutNameUsesNumber() {
        val sms = SmsPayload(
            id = "s2",
            from = "phone",
            to = "*",
            sentAtEpochMillis = 900L,
            senderNumber = "12345",
            senderName = null,
            text = "hi",
            postedAtEpochMillis = 900L,
        )
        assertEquals("12345", displayFor(received(sms))!!.title)
    }

    /** 表示対象外の payload（command 等）は null を返す。 */
    @Test
    fun nonDisplayablePayloadReturnsNull() {
        val command = CommandPayload(
            id = "c1",
            from = "desk",
            to = "phone",
            sentAtEpochMillis = 900L,
            command = CommandType.DISMISS,
            targetNotificationKey = "0|com.example|1|null|10",
        )
        assertNull(displayFor(received(command)))
    }

    private fun message(text: String = "会議は 15 時からです", fromName: String? = "xia-phone") = ReceivedMessage(
        id = "m1",
        timestampEpochMillis = 1_000L,
        payload = MessagePayload(
            id = "m1",
            from = "phone",
            to = "*",
            sentAtEpochMillis = 900L,
            text = text,
            fromName = fromName,
        ),
    )

    /** fromName があればそれをタイトルにする。 */
    @Test
    fun messageMapsFromNameToTitle() {
        val display = displayFor(message(fromName = "xia-phone"))
        assertEquals("xia-phone", display.title)
        assertEquals("会議は 15 時からです", display.body)
        assertNull(display.source)
    }

    /** fromName が無ければ from（deviceId）をタイトルにする。 */
    @Test
    fun messageWithoutFromNameFallsBackToFrom() {
        assertEquals("phone", displayFor(message(fromName = null)).title)
    }

    /** 本文が空なら固定の代替本文になる。 */
    @Test
    fun messageBlankBodyFallsBackToConstant() {
        assertEquals("（本文なし）", displayFor(message(text = "")).body)
    }

    /** 元通知のロック画面可視性は表示内容へそのまま写る。 */
    @Test
    fun notificationCarriesVisibility() {
        assertEquals(
            NotificationVisibility.PUBLIC,
            displayFor(received(notification(visibility = NotificationVisibility.PUBLIC)))!!.visibility,
        )
        assertEquals(
            NotificationVisibility.SECRET,
            displayFor(received(notification(visibility = NotificationVisibility.SECRET)))!!.visibility,
        )
    }

    /** 可視性を運ばない（旧バージョン由来の）通知は伏せる側の既定になる。 */
    @Test
    fun notificationWithoutVisibilityDefaultsToPrivate() {
        assertEquals(
            NotificationVisibility.PRIVATE,
            displayFor(received(notification(visibility = null)))!!.visibility,
        )
    }

    /** SMS と受信メッセージは元通知を持たないため、常に伏せる側で表示する。 */
    @Test
    fun smsAndMessageAreAlwaysPrivate() {
        val sms = SmsPayload(
            id = "s4",
            from = "phone",
            to = "*",
            sentAtEpochMillis = 900L,
            senderNumber = "+81900000000",
            text = "コードは 999999 です",
            postedAtEpochMillis = 900L,
        )
        assertEquals(NotificationVisibility.PRIVATE, displayFor(received(sms))!!.visibility)
        assertEquals(NotificationVisibility.PRIVATE, displayFor(message(fromName = "phone")).visibility)
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

    private fun file(
        vararg fileNames: String,
        priority: Priority = Priority.NORMAL,
        expiresAt: Long? = null,
        caption: String? = null,
    ) =
        ReceivedFile(
            id = "f1",
            timestampEpochMillis = 1_000L,
            payload = FilePayload(
                id = "f1",
                from = "desk",
                to = "*",
                sentAtEpochMillis = 900L,
                attachments = fileNames.map { attachment(it) },
                postedAtEpochMillis = 900L,
                priority = priority,
                expiresAtEpochMillis = expiresAt,
                fromName = "desk-pc",
                caption = caption,
            ),
        )

    /** キャプション付きの受信ファイルは、ファイル名の次の行にキャプションを続ける。 */
    @Test
    fun receivedFileShowsCaptionAfterFileName() {
        assertEquals("photo.jpg\n旅行の写真", displayFor(file("photo.jpg", caption = "旅行の写真")).body)
    }

    /** 空白だけのキャプションは本文に足さない。 */
    @Test
    fun receivedFileIgnoresBlankCaption() {
        assertEquals("photo.jpg", displayFor(file("photo.jpg", caption = " ")).body)
    }

    /** 受信ファイルは見出しと先頭ファイル名を載せ、送信元の端末名を発信元として添える。 */
    @Test
    fun receivedFileShowsFileNameAndSource() {
        val display = displayFor(file("photo.jpg"))
        assertEquals("f1", display.id)
        assertEquals("ファイルを受信しました", display.title)
        assertEquals("photo.jpg", display.body)
        assertEquals("desk-pc", display.source)
    }

    /** 複数ファイルの受信は、先頭ファイル名に残りの件数を添える。 */
    @Test
    fun receivedFilesShowRemainingCount() {
        assertEquals("a.jpg ほか 2 件", displayFor(file("a.jpg", "b.jpg", "c.jpg")).body)
    }

    /** 受信ファイルの表示は、ペイロードの優先度と失効時刻を引き継ぐ。 */
    @Test
    fun receivedFileCarriesPriorityAndExpiry() {
        val display = displayFor(file("photo.jpg", priority = Priority.HIGH, expiresAt = 5_000L))
        assertEquals(Priority.HIGH, display.priority)
        assertEquals(5_000L, display.expiresAtEpochMillis)
    }

    /** 優先度は対応する通知チャネル区分へ一対一で写る。 */
    @Test
    fun priorityMapsToChannelKind() {
        assertEquals(NotificationChannelKind.HIGH, channelKindFor(Priority.HIGH))
        assertEquals(NotificationChannelKind.NORMAL, channelKindFor(Priority.NORMAL))
        assertEquals(NotificationChannelKind.LOW, channelKindFor(Priority.LOW))
    }

    private fun error(message: String = "通知の復号に失敗しました", kind: ErrorKind = ErrorKind.DECRYPTION) = ErrorItem(
        id = "e1",
        timestampEpochMillis = 1_000L,
        message = message,
        kind = kind,
    )

    /** エラーは種別に応じた件名とメッセージ本文を載せ、エラーの知らせである印を付ける。 */
    @Test
    fun errorShowsTitleAndMessage() {
        val display = displayFor(error())
        assertEquals("e1", display.id)
        assertEquals("Peranta 受信エラー", display.title)
        assertEquals("通知の復号に失敗しました", display.body)
        assertTrue(display.isError)
    }

    /** メッセージが空白だけのエラーは固定の代替本文になる。 */
    @Test
    fun errorBlankMessageFallsBackToConstant() {
        assertEquals("（本文なし）", displayFor(error(message = " ")).body)
    }

    /** エラーは優先度が通常で、失効せず、発信元・元通知のキー・URL を持たず、ロック画面で伏せる。 */
    @Test
    fun errorHasNoSourceExpiryOrActions() {
        val display = displayFor(error())
        assertEquals(Priority.NORMAL, display.priority)
        assertNull(display.expiresAtEpochMillis)
        assertNull(display.source)
        assertNull(display.notificationKey)
        assertNull(display.openUrl)
        assertEquals(NotificationVisibility.PRIVATE, display.visibility)
    }

    /** 受信アイテムの表示内容はエラーの印を持たない。 */
    @Test
    fun receivedItemsAreNotMarkedAsError() {
        assertFalse(displayFor(received(notification()))!!.isError)
        assertFalse(displayFor(message()).isError)
        assertFalse(displayFor(file("photo.jpg")).isError)
    }

    /** 表示内容の時刻は、種別に依らずタイムラインのアイテムの時刻（OS 通知の時刻に当てる）になる。 */
    @Test
    fun displayCarriesItemTimestamp() {
        assertEquals(1_000L, displayFor(received(notification()))!!.timestampEpochMillis)
        assertEquals(1_000L, displayFor(message()).timestampEpochMillis)
        assertEquals(1_000L, displayFor(file("photo.jpg")).timestampEpochMillis)
        assertEquals(1_000L, displayFor(error()).timestampEpochMillis)
    }

    /** 通知・メッセージ・ファイル・エラーは、種別ごとの [displayFor] と同じ表示内容になる。 */
    @Test
    fun notificationDisplayForDispatchesByItemType() {
        val receivedNotification = received(notification())
        val receivedMessage = message()
        val receivedFile = file("photo.jpg")
        val errorItem = error()
        assertEquals(displayFor(receivedNotification), notificationDisplayFor(receivedNotification))
        assertEquals(displayFor(receivedMessage), notificationDisplayFor(receivedMessage))
        assertEquals(displayFor(receivedFile), notificationDisplayFor(receivedFile))
        assertEquals(displayFor(errorItem), notificationDisplayFor(errorItem))
    }

    /** 元通知が既に消えている受信通知は、取り下げる相手がいないので知らせない（§3.3）。 */
    @Test
    fun notificationDisplayForSkipsSourceDismissedNotification() {
        assertNull(notificationDisplayFor(received(notification()).copy(sourceDismissed = true)))
    }

    /** 表示対象外の payload（command 等）の受信アイテムは知らせない。 */
    @Test
    fun notificationDisplayForSkipsNonDisplayablePayload() {
        val command = CommandPayload(
            id = "c1",
            from = "desk",
            to = "phone",
            sentAtEpochMillis = 900L,
            command = CommandType.DISMISS,
            targetNotificationKey = "0|com.example|1|null|10",
        )
        assertNull(notificationDisplayFor(received(command)))
    }

    /** 自端末が送った通知は、通知で知らせる対象ではない。 */
    @Test
    fun notificationDisplayForSkipsSentNotification() {
        val sent = SentNotification(id = "n1", timestampEpochMillis = 1_000L, payload = notification())
        assertNull(notificationDisplayFor(sent))
    }

    /** 受信の表示内容は、優先度に対応するチャネル区分になる。 */
    @Test
    fun displayChannelKindFollowsPriority() {
        assertEquals(NotificationChannelKind.HIGH, channelKindFor(displayFor(received(notification(priority = Priority.HIGH)))!!))
        assertEquals(NotificationChannelKind.LOW, channelKindFor(displayFor(received(notification(priority = Priority.LOW)))!!))
        assertEquals(NotificationChannelKind.NORMAL, channelKindFor(displayFor(message())))
    }

    /** エラーの表示内容は、優先度に依らずエラー用のチャネル区分になる。 */
    @Test
    fun errorDisplayUsesErrorChannelKind() {
        assertEquals(NotificationChannelKind.ERROR, channelKindFor(displayFor(error())))
        assertEquals(
            NotificationChannelKind.ERROR,
            channelKindFor(displayFor(error()).copy(priority = Priority.HIGH)),
        )
    }

    /** 通知アクションのラベルは仕様の文言のまま。 */
    @Test
    fun actionLabelsKeepSpecWording() {
        assertEquals("開く", OPEN_URL_ACTION_LABEL)
        assertEquals("送信元の通知を消す", DISMISS_SOURCE_ACTION_LABEL)
    }
}
