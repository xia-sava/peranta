package to.sava.peranta.ui

import to.sava.peranta.ui.setup.ReceiveSetupSteps
import to.sava.peranta.ui.setup.SetupItemUi
import to.sava.peranta.ui.setup.SetupStatus

/** 健康診断から受信のセットアップ画面を開く誘導リンクのラベル。 */
private const val OPEN_SETUP_LABEL: String = "セットアップを開く"

/**
 * 受信のセットアップ手順の [SetupItemUi] 列を、健康診断の [HealthCheckItem] 列へ機械変換する（§10.5）。
 * UnifiedPush 系の項目は診断では修復手段（onFix）を持たず、受信のセットアップ画面への誘導だけを担う。
 * 状態は [SetupStatus] を写し取り、ラベルは「N. タイトル」、[detail] は状態の事実
 * （[SetupItemUi.statusDetail]）を基に、直接の操作が要る未達の項目にだけ [ReceiveSetupSteps.guidanceTo]
 * の誘導文を添える。合格（DONE）の項目には誘導リンクを出さない。
 */
fun receiveSetupHealthItems(
    items: List<SetupItemUi>,
    onOpenSetup: () -> Unit,
): List<HealthCheckItem> =
    items.map { item ->
        HealthCheckItem(
            id = item.id,
            label = "${ReceiveSetupSteps.numberOf(item.id)}. ${item.title}",
            state = healthStateOf(item.status),
            detail = healthDetailOf(item),
            link = if (item.status == SetupStatus.DONE) null else HealthCheckLink(OPEN_SETUP_LABEL, onOpenSetup),
        )
    }

/**
 * セットアップ項目の状態を診断の状態へ写す。要件充足（DONE）は合格に、直接の操作が要る未達（TODO）と
 * 前提未達（BLOCKED）は対処を促す不合格に、直接検査できない未確認（UNKNOWN）は合否を出さない情報にする。
 */
private fun healthStateOf(status: SetupStatus): HealthCheckState =
    when (status) {
        SetupStatus.DONE -> HealthCheckState.PASS
        SetupStatus.TODO, SetupStatus.BLOCKED -> HealthCheckState.FAILING
        SetupStatus.UNKNOWN -> HealthCheckState.INFO
    }

/**
 * 診断行の説明文。状態の事実（[SetupItemUi.statusDetail]）を基に、直接の操作が要る未達（TODO）では
 * 自手順への誘導文を添える。前提未達（BLOCKED）は事実が既に先行手順を指すため誘導を重ねず、
 * 未確認（UNKNOWN）・合格（DONE）は事実だけを示す。
 */
private fun healthDetailOf(item: SetupItemUi): String? {
    val guidance = if (item.status == SetupStatus.TODO) ReceiveSetupSteps.guidanceTo(item.id) else null
    return listOfNotNull(item.statusDetail, guidance).joinToString("\n").ifEmpty { null }
}
