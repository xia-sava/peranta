package to.sava.peranta.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class NtfyReconnectTest {

    /** 初回接続は since を付けない。 */
    @Test
    fun firstConnectHasNoSince() {
        assertEquals("ws://h:8090/t/ws", ntfyWsUrl(useTls = false, authority = "h:8090", topic = "t", since = null))
    }

    /** 再接続は最終受信メッセージ ID を since として付ける（ID は排他境界なので同じイベントは再配送されない）。 */
    @Test
    fun reconnectAppendsSinceId() {
        assertEquals(
            "ws://h:8090/t/ws?since=ElrahbvHyZb0",
            ntfyWsUrl(useTls = false, authority = "h:8090", topic = "t", since = "ElrahbvHyZb0"),
        )
    }

    /** TLS 有効時は wss スキームになる。 */
    @Test
    fun tlsUsesWssScheme() {
        assertEquals("wss://peranta.example/t/ws", ntfyWsUrl(useTls = true, authority = "peranta.example", topic = "t", since = null))
    }

    /** バックオフは 2 倍ずつ増え、上限で頭打ちになる。 */
    @Test
    fun backoffDoublesUntilCapped() {
        assertEquals(2.seconds, nextBackoff(1.seconds))
        assertEquals(60.seconds, nextBackoff(40.seconds))
        assertEquals(60.seconds, nextBackoff(60.seconds))
    }
}
