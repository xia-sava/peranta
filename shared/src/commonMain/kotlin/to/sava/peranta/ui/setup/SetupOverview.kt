package to.sava.peranta.ui.setup

import to.sava.peranta.ui.HealthCheckItem
import to.sava.peranta.ui.HealthCheckState

/** セットアップ状況の 1 行の到達状態。達成 / 未達 / 未確認の三値。 */
enum class SetupOverviewStatus { MET, UNMET, UNKNOWN }

/**
 * セットアップ状況の 1 行が誘導する画面。
 */
enum class SetupOverviewTarget { PairingImport, HealthCheck, ReceiveSetup }

/**
 * 「セットアップ状況」セクションの 1 行（§10.2）。機能単位で「何を設定するのに何が必要か」を示す。
 * [status] は状態バッジ、[detail] は事実記述、[openLabel] と [target] は誘導先を持つ行の [開く] 導線。
 */
data class SetupOverviewRow(
    val id: String,
    val title: String,
    val status: SetupOverviewStatus,
    val detail: String?,
    val openLabel: String?,
    val target: SetupOverviewTarget?,
)

/** 行①接続とペアリングの id。 */
const val OVERVIEW_ROW_CONNECTION: String = "connection"

/** 行②通知の転送の id。 */
const val OVERVIEW_ROW_FORWARD: String = "forward"

/** 行③受信経路の id。 */
const val OVERVIEW_ROW_RECEIVE: String = "receive"

/** 取得前（取得処理の完了待ち）に添える案内文。 */
private const val OVERVIEW_CHECKING_DETAIL: String = "確認中です。"

/** 受信のセットアップ手順は行③受信経路が代表するため、行②の集計から除く。 */
private val FORWARD_EXCLUDED_IDS: Set<String> = ReceiveSetupSteps.orderedIds.toSet()

/**
 * 「セットアップ状況」セクションの行モデルを組む純関数（§10.2）。既存の状態値の合成だけを行い、
 * 新たな判定は発明しない。
 *
 * 行①接続とペアリングは接続先（[hasHost]・[hasToken]）と共有鍵（[hasSharedKey]）の設定有無から
 * 達成/未達を出し、未達なら足りない項目を列挙する。誘導先は他端末から接続設定と暗号キーを取り込む画面で、
 * 達成状態でも常設する（鍵ローテ後の読み直し等、再取り込みの余地があるため）。
 * 行②通知の転送はこの端末の権限・常駐系の動作チェック項目（[healthItems]、受信経路の項目は行③が持つため除く）
 * の未達数を集計する。[healthItems] が null のときは取得前とみなし未確認にする。
 * 行③受信経路は受信のセットアップ項目（[receiveSetupItems]）の未達・未確認を集計する。
 * [hasReceiveSetup] が false のプラットフォーム（Desktop）ではこの行を出さない。
 * [receiveSetupItems] が null のときは取得前とみなし未確認にする。
 */
fun setupOverview(
    hasHost: Boolean,
    hasToken: Boolean,
    hasSharedKey: Boolean,
    healthItems: List<HealthCheckItem>?,
    hasReceiveSetup: Boolean,
    receiveSetupItems: List<SetupItemUi>?,
): List<SetupOverviewRow> = buildList {
    add(connectionRow(hasHost = hasHost, hasToken = hasToken, hasSharedKey = hasSharedKey))
    add(forwardRow(healthItems))
    if (hasReceiveSetup) {
        add(receiveRow(receiveSetupItems))
    }
}

private fun connectionRow(hasHost: Boolean, hasToken: Boolean, hasSharedKey: Boolean): SetupOverviewRow {
    val missing = buildList {
        if (!hasHost) add("サーバホスト名")
        if (!hasToken) add("アクセストークン")
        if (!hasSharedKey) add("共有鍵")
    }
    return SetupOverviewRow(
        id = OVERVIEW_ROW_CONNECTION,
        title = "接続とペアリング",
        status = if (missing.isEmpty()) SetupOverviewStatus.MET else SetupOverviewStatus.UNMET,
        detail = if (missing.isEmpty()) "接続先と共有鍵設定済み" else "未設定: ${missing.joinToString("・")}",
        openLabel = "接続設定と暗号キーを取り込む",
        target = SetupOverviewTarget.PairingImport,
    )
}

private fun forwardRow(healthItems: List<HealthCheckItem>?): SetupOverviewRow {
    val (status, detail) = forwardStatus(healthItems)
    return SetupOverviewRow(
        id = OVERVIEW_ROW_FORWARD,
        title = "通知の転送（この端末から送る）",
        status = status,
        detail = detail,
        openLabel = "動作チェックを開く",
        target = SetupOverviewTarget.HealthCheck,
    )
}

private fun forwardStatus(healthItems: List<HealthCheckItem>?): Pair<SetupOverviewStatus, String?> {
    if (healthItems == null) return SetupOverviewStatus.UNKNOWN to OVERVIEW_CHECKING_DETAIL
    val failing = healthItems
        .filterNot { it.id in FORWARD_EXCLUDED_IDS }
        .count { it.state == HealthCheckState.FAILING }
    return if (failing > 0) {
        SetupOverviewStatus.UNMET to "未達${failing}件"
    } else {
        SetupOverviewStatus.MET to null
    }
}

private fun receiveRow(receiveSetupItems: List<SetupItemUi>?): SetupOverviewRow {
    val (status, detail) = receiveStatus(receiveSetupItems)
    return SetupOverviewRow(
        id = OVERVIEW_ROW_RECEIVE,
        title = "受信経路（他の端末から受け取る）",
        status = status,
        detail = detail,
        openLabel = "受信のセットアップを開く",
        target = SetupOverviewTarget.ReceiveSetup,
    )
}

private fun receiveStatus(items: List<SetupItemUi>?): Pair<SetupOverviewStatus, String?> {
    if (items == null) return SetupOverviewStatus.UNKNOWN to OVERVIEW_CHECKING_DETAIL
    val unmet = items.count { it.status == SetupStatus.TODO || it.status == SetupStatus.BLOCKED }
    return when {
        unmet > 0 -> SetupOverviewStatus.UNMET to "未達${unmet}件"
        items.any { it.status == SetupStatus.UNKNOWN } ->
            SetupOverviewStatus.UNKNOWN to "未確認（受信テスト未実行）"
        else -> SetupOverviewStatus.MET to null
    }
}
