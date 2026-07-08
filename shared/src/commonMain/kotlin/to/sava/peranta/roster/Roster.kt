package to.sava.peranta.roster

import to.sava.peranta.model.PresencePayload

/**
 * ロスター上の 1 端末（§3.5）。presence メッセージから構築する。
 * [deviceId] は端末の安定 ID（presence の from）、[deviceName] は表示名。
 * [endpoint] は宛先エンドポイント URL、[lastUpdatedEpochMillis] は採用した presence の送信時刻。
 */
data class RosterEntry(
    val deviceId: String,
    val deviceName: String,
    val endpoint: String,
    val capabilities: List<String>,
    val sender: Boolean,
    val lastUpdatedEpochMillis: Long,
)

/**
 * presence メッセージ群からロスターを構築する。
 * 同じ deviceId（presence の from）が複数あれば送信時刻が最新のものを採用し、
 * deviceId 昇順で安定した並びにする。
 */
fun buildRoster(presences: List<PresencePayload>): List<RosterEntry> =
    presences
        .groupBy { it.from }
        .map { (deviceId, group) ->
            val latest = group.maxBy { it.sentAtEpochMillis }
            RosterEntry(
                deviceId = deviceId,
                deviceName = latest.deviceName,
                endpoint = latest.endpoint,
                capabilities = latest.capabilities,
                sender = latest.sender,
                lastUpdatedEpochMillis = latest.sentAtEpochMillis,
            )
        }
        .sortedBy { it.deviceId }

/** エンドポイント URL から publish 先の topic 名（末尾パスセグメント）を取り出す。 */
fun topicOf(endpoint: String): String = endpoint.trimEnd('/').substringAfterLast('/')

/**
 * `to: "*"` の fan-out 宛先を解決する（§8）。
 * ロスターの自分以外の全端末のエンドポイント topic を返す。
 * ロスターから宛先が得られない場合は [fallback]（静的な配送先 topic）へ退避する。
 */
fun resolveDeliveryTargets(
    roster: List<RosterEntry>,
    selfDeviceId: String?,
    fallback: List<String>,
): List<String> =
    roster
        .filter { it.deviceId != selfDeviceId }
        .map { topicOf(it.endpoint) }
        .filter { it.isNotBlank() }
        .distinct()
        .ifEmpty { fallback }

/**
 * [RosterStore.fetch] の結果から配送先 topic を解決する（§8）。
 * 取得に成功していれば [resolveDeliveryTargets] と同じ規則（自分以外・空なら [fallback]）で解決する。
 * 取得自体が失敗した場合は「解決不能」として扱い、[fallback] へは退避せず空を返す。
 * fetch 失敗を空ロスターと同一視して静的フォールバックへ無自覚に流れ込むことを避けるため。
 */
fun resolveDeliveryTopics(
    result: RosterFetchResult,
    selfDeviceId: String?,
    fallback: List<String>,
): List<String> = when (result) {
    is RosterFetchResult.Fetched -> resolveDeliveryTargets(result.entries, selfDeviceId, fallback)
    RosterFetchResult.FetchFailed -> emptyList()
}
