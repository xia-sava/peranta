package to.sava.peranta.ui

import to.sava.peranta.net.EndpointServerMatch

/** 受信エンドポイントのサーバ照合項目の id。 */
private const val ENDPOINT_SERVER_ITEM_ID: String = "up-endpoint-server"

/** 受信エンドポイントのサーバ照合項目のラベル。 */
private const val ENDPOINT_SERVER_ITEM_LABEL: String = "受信エンドポイントのサーバ"

/**
 * エンドポイント整合の診断項目を組む（§10.5）。
 * UnifiedPush 払い出しエンドポイントがこのアプリの設定サーバを向いているかの静的照合結果を項目へ写す。
 * [match] が null（endpoint 未払い出し）のときは対象外にする。
 */
fun endpointServerItem(
    match: EndpointServerMatch?,
    onReregister: (() -> Unit)?,
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
            fixGuidance = "先に ntfy アプリ側の設定変更が必要です。\n" +
                "1. ntfy アプリの 設定 →「既定のサーバー」に ${match.configOrigin} を入力する\n" +
                "2. 同じく 設定 →「ユーザーの管理」で、このサーバーのユーザー名とパスワードを追加する\n" +
                "ここまで済んでいれば「続ける」で UnifiedPush を登録し直し、" +
                "新しいサーバーのエンドポイントを受け取ります。" +
                "まだの場合は「やめる」で戻り、ntfy アプリで設定してから再度実行してください。",
        )

        EndpointServerMatch.Unparseable -> HealthCheckItem(
            id = ENDPOINT_SERVER_ITEM_ID,
            label = ENDPOINT_SERVER_ITEM_LABEL,
            state = HealthCheckState.FAILING,
            detail = "受信エンドポイント URL を解釈できません。UnifiedPush を登録し直してください。",
            fixLabel = "登録し直す",
            onFix = onReregister,
        )
    }
}
