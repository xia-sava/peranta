package to.sava.peranta.net

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * テスト用の [NtfyClient]。subscribe は与えた Flow をそのまま返し、publish は記録する。
 */
class FakeNtfyClient(
    private val events: Flow<NtfyEvent> = emptyFlow(),
) : NtfyClient {

    override val connectionState: StateFlow<NtfyConnectionState> =
        MutableStateFlow(NtfyConnectionState.SUBSCRIBED).asStateFlow()

    data class Published(val topic: String, val body: String, val cacheSeconds: Int?)

    val published = mutableListOf<Published>()

    override suspend fun publish(topic: String, body: String, cacheSeconds: Int?) {
        published.add(Published(topic, body, cacheSeconds))
    }

    override fun subscribe(topic: String): Flow<NtfyEvent> = events
}
