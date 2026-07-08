package to.sava.peranta.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class NtfyReconnectTest {

    /** 初回接続は since を付けない。 */
    @Test
    fun firstConnectHasNoSince() {
        assertEquals("ws://h:8090/t/ws", ntfyWsUrl(useTls = false, authority = "h:8090", topic = "t", since = null))
    }

    /** 再接続は最終受信 time を since として付ける。 */
    @Test
    fun reconnectAppendsSince() {
        assertEquals("ws://h:8090/t/ws?since=42", ntfyWsUrl(useTls = false, authority = "h:8090", topic = "t", since = 42))
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

    /** ウォッチドッグ: しきい値内に完了すればその値を返す。 */
    @Test
    fun receiveWithinReturnsValueWhenFast() = runTest {
        val result = receiveWithin(90.seconds) {
            delay(1.seconds)
            42
        }
        assertEquals(42, result)
    }

    /** ウォッチドッグ: しきい値を超えると null を返す（無応答＝再接続契機）。 */
    @Test
    fun receiveWithinTimesOut() = runTest {
        val result = receiveWithin(90.seconds) {
            delay(100.seconds)
            42
        }
        assertNull(result)
    }
}
