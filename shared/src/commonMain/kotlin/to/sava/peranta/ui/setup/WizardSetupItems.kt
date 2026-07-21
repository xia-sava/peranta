package to.sava.peranta.ui.setup

/**
 * 権限系のセットアップ項目 1 件を組む純関数。判定（[granted]）と操作（[onFix]）・文言は呼び出し側から渡され、
 * この関数は [SetupStatus] への写し取りと位置不変の主ボタンの組み立てだけを担う。
 * 許可済みでも操作ボタンは同じ位置に残す（再確認・設定画面への再訪のため）。
 */
fun permissionSetupItem(
    id: String,
    title: String,
    description: String,
    granted: Boolean,
    actionLabel: String,
    onFix: () -> Unit,
): SetupItemUi =
    SetupItemUi(
        id = id,
        title = title,
        description = description,
        status = if (granted) SetupStatus.DONE else SetupStatus.TODO,
        statusDetail = null,
        action = SetupAction(label = actionLabel, run = onFix),
    )
