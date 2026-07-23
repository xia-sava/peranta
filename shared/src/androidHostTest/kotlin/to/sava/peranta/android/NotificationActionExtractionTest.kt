package to.sava.peranta.android

import android.app.Notification
import to.sava.peranta.model.NotificationActionDetail
import to.sava.peranta.model.SemanticActionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationActionExtractionTest {

    /** SEMANTIC_ACTION_NONE（未設定）は null に写る。 */
    @Test
    fun noneMapsToNull() {
        assertNull(semanticActionKindOf(Notification.Action.SEMANTIC_ACTION_NONE))
    }

    /** 現行 API が定義する全 SEMANTIC_ACTION_* 定数が対応する SemanticActionKind に写る。 */
    @Test
    fun allKnownSemanticActionsMap() {
        val expected = mapOf(
            Notification.Action.SEMANTIC_ACTION_REPLY to SemanticActionKind.REPLY,
            Notification.Action.SEMANTIC_ACTION_MARK_AS_READ to SemanticActionKind.MARK_AS_READ,
            Notification.Action.SEMANTIC_ACTION_MARK_AS_UNREAD to SemanticActionKind.MARK_AS_UNREAD,
            Notification.Action.SEMANTIC_ACTION_DELETE to SemanticActionKind.DELETE,
            Notification.Action.SEMANTIC_ACTION_ARCHIVE to SemanticActionKind.ARCHIVE,
            Notification.Action.SEMANTIC_ACTION_MUTE to SemanticActionKind.MUTE,
            Notification.Action.SEMANTIC_ACTION_UNMUTE to SemanticActionKind.UNMUTE,
            Notification.Action.SEMANTIC_ACTION_THUMBS_UP to SemanticActionKind.THUMBS_UP,
            Notification.Action.SEMANTIC_ACTION_THUMBS_DOWN to SemanticActionKind.THUMBS_DOWN,
            Notification.Action.SEMANTIC_ACTION_CALL to SemanticActionKind.CALL,
        )
        expected.forEach { (raw, kind) -> assertEquals(kind, semanticActionKindOf(raw), "raw=$raw") }
    }

    /** 未知の生値（将来 Android が値を追加した場合を想定）は null に落ちる。 */
    @Test
    fun unknownRawValueMapsToNull() {
        assertNull(semanticActionKindOf(999))
    }

    /** actionDetailOf は 3 つの生シグナルをそのまま NotificationActionDetail に組む。 */
    @Test
    fun actionDetailOfComposesAllSignals() {
        val detail = actionDetailOf(
            rawSemanticAction = Notification.Action.SEMANTIC_ACTION_REPLY,
            hasRemoteInput = true,
            isActivity = null,
        )
        assertEquals(
            NotificationActionDetail(semanticAction = SemanticActionKind.REPLY, hasRemoteInput = true, opensActivity = null),
            detail,
        )
    }

    /** isActivity は API 31 未満送信元を想定した null をそのまま透過する。 */
    @Test
    fun actionDetailOfPassesThroughNullIsActivity() {
        val detail = actionDetailOf(
            rawSemanticAction = Notification.Action.SEMANTIC_ACTION_NONE,
            hasRemoteInput = false,
            isActivity = null,
        )
        assertEquals(NotificationActionDetail(), detail)
    }

    /** isActivity=true（API 31+ で発火先が Activity）はそのまま opensActivity に反映される。 */
    @Test
    fun actionDetailOfPassesThroughTrueIsActivity() {
        val detail = actionDetailOf(
            rawSemanticAction = Notification.Action.SEMANTIC_ACTION_NONE,
            hasRemoteInput = false,
            isActivity = true,
        )
        assertEquals(NotificationActionDetail(opensActivity = true), detail)
    }
}
