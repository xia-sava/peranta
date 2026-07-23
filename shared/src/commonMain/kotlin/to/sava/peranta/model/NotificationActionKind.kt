package to.sava.peranta.model

/** 受信側から見た通知アクションの実行のされ方（§3.4）。 */
enum class ActionExecutionKind {
    /** インライン返信。受信側で本文を入力し reply コマンドを送る。 */
    REPLY,

    /** 発出元で効果が完結する操作（既読化・アーカイブ等）。invokeAction を送る（従来どおり）。 */
    SENDER_EFFECT,

    /** 発出元で画面が開く・通話が始まる操作。invokeAction を送るが、結果は発出元に現れる。 */
    OPENS_ON_SENDER,

    /** 分類材料が無い（旧送信元・シグナル無し）。従来どおり invokeAction。 */
    UNKNOWN,
}

/** 発出元で効果が完結するとみなす意味分類の集合。 */
private val SENDER_EFFECT_SEMANTIC_ACTIONS = setOf(
    SemanticActionKind.MARK_AS_READ,
    SemanticActionKind.MARK_AS_UNREAD,
    SemanticActionKind.DELETE,
    SemanticActionKind.ARCHIVE,
    SemanticActionKind.MUTE,
    SemanticActionKind.UNMUTE,
    SemanticActionKind.THUMBS_UP,
    SemanticActionKind.THUMBS_DOWN,
)

/** [detail]（無ければ null）からアクションの実行分類を導く純関数（§3.4）。 */
fun classifyAction(detail: NotificationActionDetail?): ActionExecutionKind {
    if (detail == null) return ActionExecutionKind.UNKNOWN
    if (detail.hasRemoteInput || detail.semanticAction == SemanticActionKind.REPLY) {
        return ActionExecutionKind.REPLY
    }
    if (detail.semanticAction in SENDER_EFFECT_SEMANTIC_ACTIONS) {
        return ActionExecutionKind.SENDER_EFFECT
    }
    if (detail.semanticAction == SemanticActionKind.CALL) {
        return ActionExecutionKind.OPENS_ON_SENDER
    }
    return when (detail.opensActivity) {
        true -> ActionExecutionKind.OPENS_ON_SENDER
        false -> ActionExecutionKind.SENDER_EFFECT
        null -> ActionExecutionKind.UNKNOWN
    }
}

/** [index] 番のアクションの分類シグナル。actionDetails が無い・ズレている場合は null。 */
fun NotificationPayload.actionDetailAt(index: Int): NotificationActionDetail? =
    actionDetails.takeIf { it.size == actions.size }?.getOrNull(index)

/** [index] 番のアクションの実行分類。 */
fun NotificationPayload.actionKindAt(index: Int): ActionExecutionKind =
    classifyAction(actionDetailAt(index))
