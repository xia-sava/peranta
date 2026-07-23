package to.sava.peranta.android

/**
 * 通知操作コマンド（§3.4）の実行に失敗したときのユーザー向け文言。
 * 対象通知が既に消えている等の理由で発生し、いずれの場合も原因の推定はできるが対象を
 * 一意に特定する情報（notificationKey・actionIndex）は技術的な識別子でしかなくユーザーに
 * 意味を持たないため含めない。詳細は実行側が Logger に残す。
 */
const val NOTIFICATION_ACTION_FAILED_MESSAGE =
    "通知のアクションを実行できませんでした。通知はすでに消えている可能性があります。"

/** インライン返信の発火に失敗したときのユーザー向け文言。[NOTIFICATION_ACTION_FAILED_MESSAGE] と同じ理由で識別子は含めない。 */
const val NOTIFICATION_REPLY_FAILED_MESSAGE =
    "通知への返信を送信できませんでした。通知はすでに消えている可能性があります。"

/** 対象通知は存在するが返信可能な入力欄が無いときのユーザー向け文言。 */
const val NOTIFICATION_REPLY_UNSUPPORTED_MESSAGE =
    "この通知は返信に対応していません。"

/** 対象通知は存在するが指定のアクション番号に対応するアクションが無いときのユーザー向け文言。 */
const val NOTIFICATION_ACTION_INDEX_MISSING_MESSAGE =
    "指定のアクションが見つかりませんでした。通知の内容が更新された可能性があります。"
