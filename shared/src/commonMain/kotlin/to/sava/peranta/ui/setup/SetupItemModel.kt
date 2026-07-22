package to.sava.peranta.ui.setup

import to.sava.peranta.ui.FixAid

/**
 * セットアップ項目の状態。受信のセットアップ常設画面とウィザードの項目ページで共有する。
 * [DONE] は要件充足、[TODO] は未達で操作が要る、[BLOCKED] は前提未達で今は進めない、
 * [UNKNOWN] は直接検査できず未確認（エンドポイント未払い出し時の手順2 等）を表す。
 */
enum class SetupStatus {
    DONE,
    TODO,
    BLOCKED,
    UNKNOWN,
}

/**
 * セットアップ項目 1 件の表示モデル。動作チェックの項目 id と同じ体系の [id] を持ち、
 * 常設画面（[SetupChecklist] の常設モード）とウィザードの項目ページが同じ列を描く。
 * [description] は手段の説明（[ReceiveSetupSteps] や probe 定数が単一所有する文言）、
 * [statusDetail] は照合結果・テスト結果などの事実、[aids] はコピー／外部起動の補助操作、
 * [action] は状態でラベルだけ変わる位置不変の主ボタン。
 */
data class SetupItemUi(
    val id: String,
    val title: String,
    val description: String?,
    val status: SetupStatus,
    val statusDetail: String?,
    val aids: List<FixAid> = emptyList(),
    val action: SetupAction? = null,
)

/** セットアップ項目の主操作。ラベルは状態で変わるが位置は固定する。 */
data class SetupAction(val label: String, val run: () -> Unit)

/** プラットフォーム依存の判定・操作から [SetupItemUi] 列を組む供給元。 */
fun interface SetupItemsProvider {
    suspend fun items(): List<SetupItemUi>
}
