package to.sava.peranta.receive

import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import to.sava.peranta.model.CommandPayload
import to.sava.peranta.model.CommandType
import to.sava.peranta.model.FilePayload
import to.sava.peranta.model.MAX_ACTION_LABEL_BYTES
import to.sava.peranta.model.MAX_FORWARDED_ACTIONS
import to.sava.peranta.model.MAX_FORWARDED_TEXT_BYTES
import to.sava.peranta.model.MAX_FORWARDED_TITLE_BYTES
import to.sava.peranta.model.MessagePayload
import to.sava.peranta.model.NotificationActionDetail
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.SemanticActionKind
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.ui.firstUrl
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** RIGHT-TO-LEFT OVERRIDE。表示順を偽装できる不可視文字。ソースに直接置けないためコードポイントで書く。 */
private val RTL_OVERRIDE = Char(0x202E)

/** LEFT-TO-RIGHT ISOLATE。 */
private val LTR_ISOLATE = Char(0x2066)

/** BACKSPACE（C0 制御文字）。 */
private val BACKSPACE = Char(0x0008)

/**
 * 受信 payload の再検証と表示文字列の正規化（§4）。
 * 上限を破った payload は**捨てずに**切り詰めて表示へ届くこと（上限を知らない旧バージョンの送信端末の
 * 通知を消さない）と、表示へ渡る文字列から制御文字・双方向制御文字が消えることを固定する。
 */
class ReceivedPayloadNormalizationTest {

    private fun notification(
        title: String = "Code",
        text: String = "123456",
        appName: String = "Bank",
        actions: List<String> = emptyList(),
        actionDetails: List<NotificationActionDetail> = emptyList(),
    ): NotificationPayload = NotificationPayload(
        id = "n1",
        from = "phone",
        to = "*",
        sentAtEpochMillis = 1,
        packageName = "com.example.bank",
        appName = appName,
        title = title,
        text = text,
        notificationKey = "0|com.example.bank|1|null|10",
        actions = actions,
        actionDetails = actionDetails,
        postedAtEpochMillis = 1,
    )

    private fun attachment(fileName: String): AttachmentRef = AttachmentRef(
        blobId = "blob-1",
        url = "https://example.com/file/abc",
        fileName = fileName,
        mimeType = "image/jpeg",
        sizeBytes = 2048,
        kind = AttachmentKind.IMAGE,
        enc = BlobEnc(keyId = "k1", saltBase64 = "AAAAAAAAAAAAAAAAAAAAAA==", chunkSize = 1_048_576, totalChunks = 1),
    )

    /** 上限を超えるアクションを持つ通知も捨てられず、上限個数まで切り詰めて届く。 */
    @Test
    fun payloadWithTooManyActionsIsTruncatedNotDropped() {
        val actions = (1..1000).map { "アクション$it" }
        val details = (1..1000).map { NotificationActionDetail(semanticAction = SemanticActionKind.REPLY) }

        val normalized = normalizeReceivedPayload(notification(actions = actions, actionDetails = details))

        val result = normalized as NotificationPayload
        assertEquals(MAX_FORWARDED_ACTIONS, result.actions.size)
        assertEquals(MAX_FORWARDED_ACTIONS, result.actionDetails.size)
        assertEquals("アクション1", result.actions.first())
    }

    /** アクション名は上限バイトへ切り詰められ、actions と actionDetails の index 対応は崩れない。 */
    @Test
    fun actionLabelsAreTruncatedKeepingIndexAlignment() {
        val normalized = normalizeReceivedPayload(
            notification(
                actions = listOf("あ".repeat(500), "短い"),
                actionDetails = listOf(NotificationActionDetail(hasRemoteInput = true), NotificationActionDetail()),
            ),
        ) as NotificationPayload

        assertTrue(normalized.actions.first().encodeToByteArray().size <= MAX_ACTION_LABEL_BYTES)
        assertEquals(2, normalized.actions.size)
        assertEquals(2, normalized.actionDetails.size)
        assertTrue(normalized.actionDetails.first().hasRemoteInput)
    }

    /** actions より長い actionDetails は actions に合わせて詰められる（存在しない index の分類が残らない）。 */
    @Test
    fun actionDetailsAreCappedToActionCount() {
        val normalized = normalizeReceivedPayload(
            notification(
                actions = listOf("返信"),
                actionDetails = (1..100).map { NotificationActionDetail() },
            ),
        ) as NotificationPayload

        assertEquals(1, normalized.actionDetails.size)
    }

    /** 上限を超える本文・タイトルも捨てられず、切り詰めて届く。 */
    @Test
    fun oversizedTitleAndTextAreTruncatedNotDropped() {
        val normalized = normalizeReceivedPayload(
            notification(title = "あ".repeat(5000), text = "い".repeat(5000)),
        ) as NotificationPayload

        assertTrue(normalized.title.encodeToByteArray().size <= MAX_FORWARDED_TITLE_BYTES)
        assertTrue(normalized.text.encodeToByteArray().size <= MAX_FORWARDED_TEXT_BYTES)
        assertTrue(normalized.title.isNotBlank())
        assertTrue(normalized.text.isNotBlank())
    }

    /** タイトル・アプリ名の改行は空白へ畳まれ、1 行になる（発信元表示を押し流せない）。 */
    @Test
    fun singleLineFieldsCollapseLineBreaks() {
        val normalized = normalizeReceivedPayload(
            notification(title = "銀行\n\nPeranta ・ 別アプリ", appName = "Bank\nApp"),
        ) as NotificationPayload

        assertEquals("銀行 Peranta ・ 別アプリ", normalized.title)
        assertEquals("Bank App", normalized.appName)
    }

    /** 制御文字・双方向制御文字は本文からもタイトルからも取り除かれる。 */
    @Test
    fun controlAndBidiCharactersAreRemoved() {
        val normalized = normalizeReceivedPayload(
            notification(title = "銀行${RTL_OVERRIDE}gpj.exe", text = "本文$LTR_ISOLATE${BACKSPACE}続き"),
        ) as NotificationPayload

        assertEquals("銀行gpj.exe", normalized.title)
        assertEquals("本文続き", normalized.text)
    }

    /** 本文の改行は残す（複数行の通知が読めなくならない）。 */
    @Test
    fun bodyKeepsMeaningfulLineBreaks() {
        val normalized = normalizeReceivedPayload(notification(text = "1 行目\n2 行目\n3 行目")) as NotificationPayload

        assertEquals("1 行目\n2 行目\n3 行目", normalized.text)
    }

    /** 空行の連続は 1 行へ畳み、前後の空行は落とす（発信元と本文の境界を曖昧にできない）。 */
    @Test
    fun blankLineRunsAreCollapsed() {
        val normalized = normalizeReceivedPayload(
            notification(text = "\n\n\n本文\n\n\n\n続き\n\n\n"),
        ) as NotificationPayload

        assertEquals("本文\n\n続き", normalized.text)
    }

    /** 正規化しても本文中の URL は壊れず、「開く」導線の抽出が従来どおり効く。 */
    @Test
    fun urlSurvivesNormalization() {
        val normalized = normalizeReceivedPayload(
            notification(text = "詳細は\nhttps://example.com/path?a=1&b=2 を見てください"),
        ) as NotificationPayload

        assertEquals("https://example.com/path?a=1&b=2", firstUrl(normalized.text))
    }

    /** SMS の送信者名・本文も同じ関門を通る。 */
    @Test
    fun smsSenderAndBodyAreNormalized() {
        val normalized = normalizeReceivedPayload(
            SmsPayload(
                id = "s1",
                from = "phone",
                to = "*",
                sentAtEpochMillis = 1,
                senderNumber = "090-1111-2222",
                senderName = "銀行\n公式",
                text = "\n\n確認コード 987654",
                postedAtEpochMillis = 1,
            ),
        ) as SmsPayload

        assertEquals("銀行 公式", normalized.senderName)
        assertEquals("確認コード 987654", normalized.text)
    }

    /** 添付のファイル名は 1 行へ正規化される。所在と復号パラメータは突き合わせに使うため触らない。 */
    @Test
    fun attachmentFileNameIsNormalizedButLocationIsUntouched() {
        val ref = attachment("photo${RTL_OVERRIDE}gnp.exe\nfake.jpg")

        val normalized = normalizeReceivedPayload(
            FilePayload(
                id = "f1",
                from = "phone",
                to = "*",
                sentAtEpochMillis = 1,
                caption = "写真",
                attachments = listOf(ref),
                postedAtEpochMillis = 1,
            ),
        ) as FilePayload

        val normalizedRef = normalized.attachments.single()
        assertEquals("photognp.exe fake.jpg", normalizedRef.fileName)
        assertEquals(ref.url, normalizedRef.url)
        assertEquals(ref.blobId, normalizedRef.blobId)
        assertEquals(ref.enc, normalizedRef.enc)
    }

    /** メッセージ本文も正規化する。 */
    @Test
    fun messageTextIsNormalized() {
        val normalized = normalizeReceivedPayload(
            MessagePayload(
                id = "m1",
                from = "phone",
                to = "*",
                sentAtEpochMillis = 1,
                text = "会議は$RTL_OVERRIDE 15 時から\n\n\n",
            ),
        ) as MessagePayload

        assertEquals("会議は 15 時から", normalized.text)
    }

    /** 表示に出ない payload（command）は素通しする。突き合わせに使う値を変えないため。 */
    @Test
    fun commandPayloadIsUntouched() {
        val command = CommandPayload(
            id = "c1",
            from = "desk",
            to = "phone",
            sentAtEpochMillis = 1,
            command = CommandType.REPLY,
            targetNotificationKey = "0|com.example.bank|1|null|10",
            actionIndex = 0,
            replyText = "了解です",
        )

        assertSame(command, normalizeReceivedPayload(command))
    }

    /** 全文添付から取り出した本文は行数では切らない（全文を読むためのものなので）。 */
    @Test
    fun fullTextKeepsAllLines() {
        val text = (1..200).joinToString("\n") { "$it 行目" }

        val normalized = normalizeFullText(text)

        assertEquals(200, normalized.lines().size)
        assertContains(normalized, "200 行目")
    }

    /** 全文添付から取り出した本文も制御文字・双方向制御文字を落とす。 */
    @Test
    fun fullTextRemovesControlCharacters() {
        assertEquals("全文です", normalizeFullText("全文${RTL_OVERRIDE}です"))
    }
}
