package to.sava.peranta.ui.setup

import to.sava.peranta.net.EndpointServerMatch
import to.sava.peranta.net.SelfTestResult
import to.sava.peranta.net.SelfTestStatus
import to.sava.peranta.ui.FixAid

/** ntfy のサーバ設定が不一致のときの事実と影響の記述。 */
private val SERVER_CONFIG_MISMATCH_DETAIL: String =
    "${ReceiveSetupSteps.labelOf(ReceiveSetupSteps.UNIFIED_PUSH_ID)}の照合で不一致です。" +
        "ntfy のデフォルトのサーバーがこのアプリの設定サーバと一致していないため、" +
        "転送された通知がこの端末に届きません。"

/** 受信テストが失敗したときに見直す手順の範囲（ntfy のサーバ設定から省電力除外まで）。 */
private val SELF_TEST_REVIEW_RANGE: String =
    ReceiveSetupSteps.rangeLabelOf(ReceiveSetupSteps.SERVER_CONFIG_ID, ReceiveSetupSteps.NTFY_BATTERY_ID)

/**
 * probe が判定した実状態から受信のセットアップ手順の [SetupItemUi] 列を組む純関数。
 * 並びは [ReceiveSetupSteps.orderedIds] に従い、番号・タイトル・説明文は同オブジェクトが単一所有する。
 * 判定・操作は呼び出し側（probe）から値と操作として渡され、この関数は状態の写し取りだけを担う。
 * 状態に依らずコピーチップ・主操作を常設し、状態バッジと事実記述だけが状態で変わる。
 */
fun receiveSetupItems(
    ntfyInstalled: Boolean,
    otherDistributors: List<String>,
    endpointMatch: EndpointServerMatch?,
    upRegistered: Boolean,
    ntfyBatteryIgnored: Boolean,
    selfTestStatus: SelfTestStatus,
    selfTestRunnable: Boolean,
    ntfyServerAids: List<FixAid>,
    onInstallNtfy: () -> Unit,
    onRegister: () -> Unit,
    onReregister: () -> Unit,
    onOpenNtfyBattery: () -> Unit,
    onRunSelfTest: () -> Unit,
): List<SetupItemUi> =
    ReceiveSetupSteps.orderedIds.map { id ->
        when (id) {
            ReceiveSetupSteps.NTFY_INSTALLED_ID ->
                ntfyInstalledItem(id, ntfyInstalled, otherDistributors, onInstallNtfy)
            ReceiveSetupSteps.SERVER_CONFIG_ID -> serverConfigItem(id, endpointMatch, ntfyServerAids)
            ReceiveSetupSteps.UNIFIED_PUSH_ID ->
                unifiedPushItem(id, upRegistered, endpointMatch, onRegister, onReregister)
            ReceiveSetupSteps.NTFY_BATTERY_ID ->
                ntfyBatteryItem(id, ntfyInstalled, ntfyBatteryIgnored, onOpenNtfyBattery)
            ReceiveSetupSteps.SELF_TEST_ID ->
                selfTestItem(id, selfTestStatus, selfTestRunnable, endpointMatch, onRunSelfTest)
            else -> throw IllegalArgumentException("未知の受信セットアップ手順: $id")
        }
    }

/**
 * ntfy 導入の手順。導入済みでもストア導線を常設する。
 * ntfy が無く他のディストリビュータだけが居るときは、それを採用しない旨を事実として添える。
 */
private fun ntfyInstalledItem(
    id: String,
    installed: Boolean,
    otherDistributors: List<String>,
    onInstallNtfy: () -> Unit,
): SetupItemUi =
    SetupItemUi(
        id = id,
        title = ReceiveSetupSteps.titleOf(id),
        description = ReceiveSetupSteps.descriptionOf(id),
        status = if (installed) SetupStatus.DONE else SetupStatus.TODO,
        statusDetail = if (installed || otherDistributors.isEmpty()) {
            null
        } else {
            otherDistributorsDetail(otherDistributors)
        },
        action = SetupAction(label = if (installed) "ストアで開く" else "インストール", run = onInstallNtfy),
    )

/** ntfy 以外のディストリビュータだけが居る状態の事実記述。 */
private fun otherDistributorsDetail(otherDistributors: List<String>): String =
    "ntfy 以外のディストリビュータ（${otherDistributors.joinToString("・")}）が導入されていますが、" +
        "自動では選びません。配信は自分の ntfy サーバを購読する ntfy アプリでのみ成立します。"

/** ntfy のサーバ設定の手順。UnifiedPush 登録の照合から三値で状態を出し、貼り付け値は常設する。 */
private fun serverConfigItem(id: String, match: EndpointServerMatch?, ntfyServerAids: List<FixAid>): SetupItemUi =
    SetupItemUi(
        id = id,
        title = ReceiveSetupSteps.titleOf(id),
        description = ReceiveSetupSteps.descriptionOf(id),
        status = when (match) {
            null -> SetupStatus.UNKNOWN
            EndpointServerMatch.Match -> SetupStatus.DONE
            EndpointServerMatch.Unparseable, is EndpointServerMatch.Mismatch -> SetupStatus.TODO
        },
        statusDetail = when (match) {
            null, EndpointServerMatch.Match -> null
            EndpointServerMatch.Unparseable -> unparseableEndpointDetail()
            is EndpointServerMatch.Mismatch -> SERVER_CONFIG_MISMATCH_DETAIL
        },
        aids = ntfyServerAids,
    )

/** エンドポイント URL を解釈できないときの事実と対処。登録し直しは UnifiedPush 登録の手順で行う。 */
private fun unparseableEndpointDetail(): String {
    val unifiedPush = ReceiveSetupSteps.labelOf(ReceiveSetupSteps.UNIFIED_PUSH_ID)
    return "${unifiedPush}の照合でエンドポイント URL を解釈できません。${unifiedPush}で登録し直してください。"
}

/** UnifiedPush 登録の手順。ラベルは登録状態で入れ替え、位置は固定する。 */
private fun unifiedPushItem(
    id: String,
    upRegistered: Boolean,
    match: EndpointServerMatch?,
    onRegister: () -> Unit,
    onReregister: () -> Unit,
): SetupItemUi =
    SetupItemUi(
        id = id,
        title = ReceiveSetupSteps.titleOf(id),
        description = ReceiveSetupSteps.descriptionOf(id),
        status = if (upRegistered) SetupStatus.DONE else SetupStatus.TODO,
        statusDetail = endpointMatchDetail(match),
        action = SetupAction(
            label = if (upRegistered) "登録し直す" else "登録する",
            run = if (upRegistered) onReregister else onRegister,
        ),
    )

/** エンドポイント照合結果の事実記述。未払い出しなら記述しない。 */
private fun endpointMatchDetail(match: EndpointServerMatch?): String? =
    when (match) {
        null -> null
        EndpointServerMatch.Match -> "受信エンドポイントはこのアプリの設定サーバと一致しています。"
        is EndpointServerMatch.Mismatch ->
            "受信エンドポイントが ${match.endpointOrigin} を向いています（設定サーバ ${match.configOrigin} と不一致）。"
        EndpointServerMatch.Unparseable -> "受信エンドポイント URL を解釈できません。"
    }

/** ntfy の省電力除外の手順。ntfy 未導入なら前提未達として進めない。 */
private fun ntfyBatteryItem(
    id: String,
    ntfyInstalled: Boolean,
    ntfyBatteryIgnored: Boolean,
    onOpenNtfyBattery: () -> Unit,
): SetupItemUi =
    SetupItemUi(
        id = id,
        title = ReceiveSetupSteps.titleOf(id),
        description = ReceiveSetupSteps.descriptionOf(id),
        status = when {
            !ntfyInstalled -> SetupStatus.BLOCKED
            ntfyBatteryIgnored -> SetupStatus.DONE
            else -> SetupStatus.TODO
        },
        statusDetail = if (ntfyInstalled) {
            null
        } else {
            "先に${ReceiveSetupSteps.labelOf(ReceiveSetupSteps.NTFY_INSTALLED_ID)}で ntfy を導入してください。"
        },
        action = SetupAction(label = "設定を開く", run = onOpenNtfyBattery),
    )

/**
 * 受信テストの手順。エンドポイント未払い出しは前提未達（BLOCKED）として UnifiedPush 登録を参照させる。
 * アクセストークン未設定は受信自体を妨げない構成なので合否を出さず（UNKNOWN）、実行に必要な旨だけ示す。
 * 前提が揃えば結果で状態を分け、ラベルは実行有無で入れ替える。失敗時の対処は自手順参照に留める。
 * ボタンは状態に依らず常設する。
 */
private fun selfTestItem(
    id: String,
    status: SelfTestStatus,
    runnable: Boolean,
    endpointMatch: EndpointServerMatch?,
    onRunSelfTest: () -> Unit,
): SetupItemUi =
    SetupItemUi(
        id = id,
        title = ReceiveSetupSteps.titleOf(id),
        description = ReceiveSetupSteps.descriptionOf(id),
        status = when {
            runnable -> selfTestStatusOf(status)
            endpointMatch == null -> SetupStatus.BLOCKED
            else -> SetupStatus.UNKNOWN
        },
        statusDetail = if (runnable) selfTestDetailOf(status) else selfTestBlockedDetail(endpointMatch),
        action = SetupAction(
            label = if (status == SelfTestStatus.NotRun) "テスト実行" else "再実行",
            run = onRunSelfTest,
        ),
    )

/** 受信テストが実行できない前提未達の理由。エンドポイント未払い出しなら UnifiedPush 登録を参照させる。 */
private fun selfTestBlockedDetail(endpointMatch: EndpointServerMatch?): String =
    if (endpointMatch == null) {
        "先に${ReceiveSetupSteps.labelOf(ReceiveSetupSteps.UNIFIED_PUSH_ID)}で登録してください。"
    } else {
        "受信テストにはアクセストークンの設定が必要です。設定画面で設定してください。"
    }

private fun selfTestStatusOf(status: SelfTestStatus): SetupStatus =
    when (status) {
        SelfTestStatus.NotRun, SelfTestStatus.Running -> SetupStatus.UNKNOWN
        is SelfTestStatus.Done ->
            if (status.result == SelfTestResult.Delivered) SetupStatus.DONE else SetupStatus.TODO
    }

private fun selfTestDetailOf(status: SelfTestStatus): String? =
    when (status) {
        SelfTestStatus.NotRun -> "まだ実行していません。"
        SelfTestStatus.Running -> "確認中です。数秒お待ちください（自動で再チェックされます）。"
        is SelfTestStatus.Done -> selfTestResultDetail(status.result)
    }

private fun selfTestResultDetail(result: SelfTestResult): String =
    when (result) {
        SelfTestResult.Delivered -> "サーバ経由の配送を確認しました。"
        SelfTestResult.Timeout ->
            "テスト通知を送信しましたが、5 秒以内に届きませんでした。${SELF_TEST_REVIEW_RANGE}を確認してください。"
        is SelfTestResult.PublishRejected ->
            "サーバが送信を拒否しました（HTTP ${result.status}）。${SELF_TEST_REVIEW_RANGE}を確認してください。"
        SelfTestResult.PublishFailed ->
            "サーバに接続できませんでした。${SELF_TEST_REVIEW_RANGE}を確認してください。"
    }
