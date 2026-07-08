package to.sava.peranta.roster

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import to.sava.peranta.net.NtfyClient
import to.sava.peranta.net.NtfyConnectionState
import to.sava.peranta.net.NtfyEvent

/**
 * ロスター系テスト用の [NtfyClient]。fetchHistory は与えた履歴（または例外）を返し、
 * publish は宛先・本文・キャッシュ秒数を記録する。
 * [historyDelayMillis] を指定すると、タイムアウト検証用に応答前に遅延する。
 */
class RecordingControlNtfy(
    private val history: List<NtfyEvent> = emptyList(),
    private val historyError: Throwable? = null,
    private val historyDelayMillis: Long = 0,
) : NtfyClient {

    override val connectionState: StateFlow<NtfyConnectionState> =
        MutableStateFlow(NtfyConnectionState.SUBSCRIBED).asStateFlow()

    data class Published(val topic: String, val body: String, val cacheSeconds: Int?)

    val published = mutableListOf<Published>()

    override suspend fun publish(topic: String, body: String, cacheSeconds: Int?) {
        published.add(Published(topic, body, cacheSeconds))
    }

    override fun subscribe(topic: String): Flow<NtfyEvent> = emptyFlow()

    override suspend fun fetchHistory(topic: String, since: String): List<NtfyEvent> {
        if (historyDelayMillis > 0) delay(historyDelayMillis)
        historyError?.let { throw it }
        return history
    }
}
