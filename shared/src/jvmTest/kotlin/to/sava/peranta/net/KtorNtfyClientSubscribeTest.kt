package to.sava.peranta.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import to.sava.peranta.config.PerantaConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class KtorNtfyClientSubscribeTest {

    /**
     * ローカルの WebSocket サーバに対し、open を受けて購読確立し、
     * message フレームを正規化イベントとして流すまでを検証する。
     */
    @Test
    fun subscribeEstablishesThenEmitsMessageEvent() = runBlocking {
        val server = embeddedServer(ServerCIO, port = 0) {
            install(ServerWebSockets)
            routing {
                webSocket("/{topic}/ws") {
                    send(Frame.Text("""{"id":"o","time":1,"event":"open","topic":"t"}"""))
                    send(Frame.Text("""{"id":"m1","time":100,"event":"message","topic":"t","message":"payload-body"}"""))
                    runCatching { incoming.consumeEach { } }
                }
            }
        }.start(wait = false)

        val client = HttpClient(ClientCIO) { install(ClientWebSockets) }
        try {
            val port = server.engine.resolvedConnectors().first().port
            val config = PerantaConfig(host = "127.0.0.1", useTls = false, port = port, accessToken = "tok")
            val ntfy = KtorNtfyClient(config, client)

            val event = withTimeout(15_000) { ntfy.subscribe("t").first() }

            assertEquals("m1", event.id)
            assertEquals(100, event.time)
            assertEquals("payload-body", event.message)
            assertEquals(NtfyConnectionState.SUBSCRIBED, ntfy.connectionState.value)
        } finally {
            client.close()
            server.stop(0, 0)
        }
    }
}
