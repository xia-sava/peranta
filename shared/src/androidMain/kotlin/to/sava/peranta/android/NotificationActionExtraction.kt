package to.sava.peranta.android

import android.app.Notification
import to.sava.peranta.model.NotificationActionDetail
import to.sava.peranta.model.SemanticActionKind

/** getSemanticAction の生値（SEMANTIC_ACTION_*）を [SemanticActionKind] に写す。NONE・未知値は null。 */
fun semanticActionKindOf(raw: Int): SemanticActionKind? = when (raw) {
    Notification.Action.SEMANTIC_ACTION_REPLY -> SemanticActionKind.REPLY
    Notification.Action.SEMANTIC_ACTION_MARK_AS_READ -> SemanticActionKind.MARK_AS_READ
    Notification.Action.SEMANTIC_ACTION_MARK_AS_UNREAD -> SemanticActionKind.MARK_AS_UNREAD
    Notification.Action.SEMANTIC_ACTION_DELETE -> SemanticActionKind.DELETE
    Notification.Action.SEMANTIC_ACTION_ARCHIVE -> SemanticActionKind.ARCHIVE
    Notification.Action.SEMANTIC_ACTION_MUTE -> SemanticActionKind.MUTE
    Notification.Action.SEMANTIC_ACTION_UNMUTE -> SemanticActionKind.UNMUTE
    Notification.Action.SEMANTIC_ACTION_THUMBS_UP -> SemanticActionKind.THUMBS_UP
    Notification.Action.SEMANTIC_ACTION_THUMBS_DOWN -> SemanticActionKind.THUMBS_DOWN
    Notification.Action.SEMANTIC_ACTION_CALL -> SemanticActionKind.CALL
    else -> null
}

/** アクション 1 個の分類シグナルを組む純関数。[isActivity] は API 31 未満なら null を渡す。 */
fun actionDetailOf(
    rawSemanticAction: Int,
    hasRemoteInput: Boolean,
    isActivity: Boolean?,
): NotificationActionDetail = NotificationActionDetail(
    semanticAction = semanticActionKindOf(rawSemanticAction),
    hasRemoteInput = hasRemoteInput,
    opensActivity = isActivity,
)
