package to.sava.peranta.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationActionKindTest {

    private fun notification(actions: List<String>, actionDetails: List<NotificationActionDetail>) = NotificationPayload(
        id = "n",
        from = "phone",
        to = "*",
        sentAtEpochMillis = 1,
        packageName = "p",
        appName = "a",
        title = "t",
        text = "b",
        notificationKey = "k",
        actions = actions,
        actionDetails = actionDetails,
        postedAtEpochMillis = 1,
    )

    /** 分類シグナルが無い（detail == null）場合は UNKNOWN になる。 */
    @Test
    fun nullDetailIsUnknown() {
        assertEquals(ActionExecutionKind.UNKNOWN, classifyAction(null))
    }

    /** hasRemoteInput が true なら他のシグナルより優先して REPLY と判定される。 */
    @Test
    fun hasRemoteInputTakesPriorityAsReply() {
        val detail = NotificationActionDetail(
            semanticAction = SemanticActionKind.ARCHIVE,
            hasRemoteInput = true,
        )
        assertEquals(ActionExecutionKind.REPLY, classifyAction(detail))
    }

    /** semanticAction が REPLY でも hasRemoteInput が false でも REPLY と判定される。 */
    @Test
    fun semanticReplyIsReplyEvenWithoutRemoteInputFlag() {
        val detail = NotificationActionDetail(semanticAction = SemanticActionKind.REPLY, hasRemoteInput = false)
        assertEquals(ActionExecutionKind.REPLY, classifyAction(detail))
    }

    /** 効果完結系の semanticAction（既読化・アーカイブ・mute 等）は SENDER_EFFECT になる。 */
    @Test
    fun senderEffectSemanticActionsAreSenderEffect() {
        val senderEffectKinds = listOf(
            SemanticActionKind.MARK_AS_READ,
            SemanticActionKind.MARK_AS_UNREAD,
            SemanticActionKind.DELETE,
            SemanticActionKind.ARCHIVE,
            SemanticActionKind.MUTE,
            SemanticActionKind.UNMUTE,
            SemanticActionKind.THUMBS_UP,
            SemanticActionKind.THUMBS_DOWN,
        )
        senderEffectKinds.forEach { kind ->
            assertEquals(
                ActionExecutionKind.SENDER_EFFECT,
                classifyAction(NotificationActionDetail(semanticAction = kind)),
                "kind=$kind",
            )
        }
    }

    /** semanticAction が CALL は OPENS_ON_SENDER になる（通話は発出元で始まる）。 */
    @Test
    fun callSemanticActionOpensOnSender() {
        assertEquals(
            ActionExecutionKind.OPENS_ON_SENDER,
            classifyAction(NotificationActionDetail(semanticAction = SemanticActionKind.CALL)),
        )
    }

    /** opensActivity=true は OPENS_ON_SENDER になる。 */
    @Test
    fun opensActivityTrueOpensOnSender() {
        assertEquals(
            ActionExecutionKind.OPENS_ON_SENDER,
            classifyAction(NotificationActionDetail(opensActivity = true)),
        )
    }

    /** opensActivity=false は SENDER_EFFECT になる（broadcast・service 発火 = 背景処理）。 */
    @Test
    fun opensActivityFalseIsSenderEffect() {
        assertEquals(
            ActionExecutionKind.SENDER_EFFECT,
            classifyAction(NotificationActionDetail(opensActivity = false)),
        )
    }

    /** シグナルが皆無（全フィールドが既定値）の detail は UNKNOWN になる。 */
    @Test
    fun noSignalsAtAllIsUnknown() {
        assertEquals(ActionExecutionKind.UNKNOWN, classifyAction(NotificationActionDetail()))
    }

    /** actionDetailAt は actions と actionDetails が同数のとき index 対応する要素を返す。 */
    @Test
    fun actionDetailAtReturnsMatchingIndexWhenSizesMatch() {
        val detail = NotificationActionDetail(opensActivity = true)
        val payload = notification(actions = listOf("消す", "地図"), actionDetails = listOf(NotificationActionDetail(), detail))
        assertEquals(detail, payload.actionDetailAt(1))
        assertEquals(ActionExecutionKind.OPENS_ON_SENDER, payload.actionKindAt(1))
    }

    /** actionDetails のサイズが actions と食い違うときは、誤った index の分類を適用せず常に null（UNKNOWN）にする。 */
    @Test
    fun actionDetailAtReturnsNullWhenSizeMismatched() {
        val payload = notification(
            actions = listOf("消す", "地図"),
            actionDetails = listOf(NotificationActionDetail(opensActivity = true)),
        )
        assertNull(payload.actionDetailAt(0))
        assertNull(payload.actionDetailAt(1))
        assertEquals(ActionExecutionKind.UNKNOWN, payload.actionKindAt(0))
    }

    /** actionDetails が空（旧送信元由来）のときは全 index が UNKNOWN になる。 */
    @Test
    fun actionDetailAtReturnsNullWhenActionDetailsEmpty() {
        val payload = notification(actions = listOf("消す"), actionDetails = emptyList())
        assertNull(payload.actionDetailAt(0))
        assertEquals(ActionExecutionKind.UNKNOWN, payload.actionKindAt(0))
    }
}
