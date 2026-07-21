package to.sava.peranta.ui

import to.sava.peranta.net.EndpointServerMatch
import to.sava.peranta.net.SelfTestResult
import to.sava.peranta.net.SelfTestStatus

/** 受信エンドポイントのサーバ照合項目の id。 */
private const val ENDPOINT_SERVER_ITEM_ID: String = "up-endpoint-server"

/** 受信エンドポイントのサーバ照合項目のラベル。 */
private const val ENDPOINT_SERVER_ITEM_LABEL: String = "受信エンドポイントのサーバ"

/** 自己疎通テストの診断項目の id。 */
private const val SELF_TEST_ITEM_ID: String = "up-self-test"

/** 自己疎通テストの診断項目のラベル。 */
private const val SELF_TEST_ITEM_LABEL: String = "サーバ経由の受信テスト"

/**
 * エンドポイント整合の診断項目を組む（§10.5）。
 * UnifiedPush 払い出しエンドポイントがこのアプリの設定サーバを向いているかの静的照合結果を項目へ写す。
 * [match] が null（endpoint 未払い出し）のときは対象外にする。
 * [fixAids] は不合格（Mismatch / Unparseable）の項目にそのまま載せる、案内ダイアログの補助操作。
 */
fun endpointServerItem(
    match: EndpointServerMatch?,
    onReregister: (() -> Unit)?,
    fixAids: List<FixAid> = emptyList(),
): HealthCheckItem {
    if (match == null) {
        return HealthCheckItem(
            id = ENDPOINT_SERVER_ITEM_ID,
            label = ENDPOINT_SERVER_ITEM_LABEL,
            state = HealthCheckState.NOT_APPLICABLE,
        )
    }
    return when (match) {
        EndpointServerMatch.Match -> HealthCheckItem(
            id = ENDPOINT_SERVER_ITEM_ID,
            label = ENDPOINT_SERVER_ITEM_LABEL,
            state = HealthCheckState.PASS,
        )

        is EndpointServerMatch.Mismatch -> HealthCheckItem(
            id = ENDPOINT_SERVER_ITEM_ID,
            label = ENDPOINT_SERVER_ITEM_LABEL,
            state = HealthCheckState.FAILING,
            detail = "受信エンドポイントが ${match.endpointOrigin} を向いています。" +
                "このアプリの設定サーバ（${match.configOrigin}）と一致しないため、" +
                "転送された通知はこの端末に届きません。" +
                "ntfy アプリの既定のサーバーを変更し、UnifiedPush を登録し直してください。",
            fixLabel = "登録し直す",
            onFix = onReregister,
            fixGuidance = "先に ntfy アプリ側の設定が必要です。下の値をコピーして ntfy アプリに貼り付けてください。\n" +
                "1. ntfy の 設定 →「既定のサーバー」に、サーバーURL を貼り付ける\n" +
                "2. 設定 → このサーバーの「カスタムヘッダー」で、ヘッダー名 Authorization に認証ヘッダの値を貼り付ける" +
                "（「ユーザーの管理」でこのサーバーのユーザーを登録済みの場合、この手順は不要）\n" +
                "3. ここへ戻って「続ける」を押すと、UnifiedPush を登録し直して新しいエンドポイントを受け取ります。",
            fixAids = fixAids,
        )

        EndpointServerMatch.Unparseable -> HealthCheckItem(
            id = ENDPOINT_SERVER_ITEM_ID,
            label = ENDPOINT_SERVER_ITEM_LABEL,
            state = HealthCheckState.FAILING,
            detail = "受信エンドポイント URL を解釈できません。UnifiedPush を登録し直してください。",
            fixLabel = "登録し直す",
            onFix = onReregister,
            fixAids = fixAids,
        )
    }
}

/**
 * 自己疎通テストの診断項目を組む（§10.5）。
 * テスト通知を自分宛にサーバ経由で送り、受信できるかを [SelfTestProbe] の結果から項目へ写す。
 * [runnable] が false の項目は「直す」導線を持たず、実行前提（endpoint・トークン・サーバ照合）が
 * 整っていない理由を、可能なら案内する。
 */
fun selfTestItem(
    status: SelfTestStatus,
    runnable: Boolean,
    serverMismatch: Boolean,
    onRun: (() -> Unit)?,
): HealthCheckItem {
    if (!runnable) {
        if (serverMismatch) {
            return HealthCheckItem(
                id = SELF_TEST_ITEM_ID,
                label = SELF_TEST_ITEM_LABEL,
                state = HealthCheckState.INFO,
                detail = "サーバ不一致（上の項目）を解消してから実行してください。",
            )
        }
        return HealthCheckItem(
            id = SELF_TEST_ITEM_ID,
            label = SELF_TEST_ITEM_LABEL,
            state = HealthCheckState.NOT_APPLICABLE,
        )
    }
    return when (status) {
        SelfTestStatus.NotRun -> HealthCheckItem(
            id = SELF_TEST_ITEM_ID,
            label = SELF_TEST_ITEM_LABEL,
            state = HealthCheckState.INFO,
            detail = "テスト通知を自分宛にサーバ経由で送り、実際に受信できるかを確認します。",
            fixLabel = "テスト実行",
            onFix = onRun,
        )

        SelfTestStatus.Running -> HealthCheckItem(
            id = SELF_TEST_ITEM_ID,
            label = SELF_TEST_ITEM_LABEL,
            state = HealthCheckState.INFO,
            detail = "確認中です。数秒お待ちください（自動で再チェックされます）。",
        )

        is SelfTestStatus.Done -> selfTestDoneItem(status.result, onRun)
    }
}

/** [SelfTestStatus.Done] の結果を診断項目へ写す。 */
private fun selfTestDoneItem(result: SelfTestResult, onRun: (() -> Unit)?): HealthCheckItem =
    when (result) {
        SelfTestResult.Delivered -> HealthCheckItem(
            id = SELF_TEST_ITEM_ID,
            label = SELF_TEST_ITEM_LABEL,
            state = HealthCheckState.PASS,
            detail = "サーバ経由の配送を確認しました。",
            fixLabel = "再実行",
            onFix = onRun,
        )

        is SelfTestResult.PublishRejected -> HealthCheckItem(
            id = SELF_TEST_ITEM_ID,
            label = SELF_TEST_ITEM_LABEL,
            state = HealthCheckState.FAILING,
            detail = if (result.status == 403) {
                "サーバが送信を拒否しました（403）。サーバの ACL で、アクセストークンのユーザーに " +
                    "up で始まるトピックへの書き込み権限が必要です（例: ntfy access <ユーザー名> 'up*' write-only）。"
            } else {
                "サーバがエラーを返しました（HTTP ${result.status}）。サーバの状態を確認してください。"
            },
            fixLabel = "再実行",
            onFix = onRun,
        )

        SelfTestResult.PublishFailed -> HealthCheckItem(
            id = SELF_TEST_ITEM_ID,
            label = SELF_TEST_ITEM_LABEL,
            state = HealthCheckState.FAILING,
            detail = "サーバに接続できませんでした。サーバのホスト名とネットワーク接続を確認してください。",
            fixLabel = "再実行",
            onFix = onRun,
        )

        SelfTestResult.Timeout -> HealthCheckItem(
            id = SELF_TEST_ITEM_ID,
            label = SELF_TEST_ITEM_LABEL,
            state = HealthCheckState.FAILING,
            detail = "テスト通知を送信しましたが、5 秒以内に届きませんでした。ntfy アプリ側の受信に問題があります。" +
                "考えられる原因: (1) ntfy アプリにこのサーバーのログイン情報が未登録（ntfy の 設定 →" +
                "「ユーザーの管理」で追加） (2) ntfy アプリが省電力の影響でポーリング受信になっている" +
                "（バッテリー最適化の除外を確認） (3) ntfy アプリの購読が切れている（ntfy アプリを一度開く）。",
            fixLabel = "再実行",
            onFix = onRun,
        )
    }
