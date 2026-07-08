package to.sava.peranta.net

import to.sava.peranta.model.PerantaJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NtfyEventTest {

    private fun parse(json: String): NtfyEvent? =
        PerantaJson.decodeFromString<NtfyWsMessage>(json).toEventOrNull()

    /** event=="message" の行は id/time/topic/message が写された NtfyEvent になる。 */
    @Test
    fun messageEventIsMapped() {
        val event = parse(
            """{"id":"abc","time":1783498756,"event":"message","topic":"peranta-x","message":"hello"}""",
        )
        assertEquals(NtfyEvent(id = "abc", time = 1783498756, topic = "peranta-x", message = "hello"), event)
    }

    /** "open" イベントは購読確立の通知なので無視され null になる。 */
    @Test
    fun openEventIsIgnored() {
        assertNull(parse("""{"id":"o1","time":1,"event":"open","topic":"peranta-x"}"""))
    }

    /** "keepalive" イベントは疎通維持用なので無視され null になる。 */
    @Test
    fun keepaliveEventIsIgnored() {
        assertNull(parse("""{"id":"k1","time":2,"event":"keepalive","topic":"peranta-x"}"""))
    }

    /** message フィールドを欠く message イベントは null になる。 */
    @Test
    fun messageEventWithoutBodyIsNull() {
        assertNull(parse("""{"id":"m0","time":3,"event":"message","topic":"peranta-x"}"""))
    }

    /** 未知フィールドがあっても既知フィールドは正しく取り出される。 */
    @Test
    fun unknownFieldsAreIgnored() {
        val event = parse(
            """{"id":"z","time":9,"event":"message","topic":"t","message":"m","priority":3,"tags":["a"]}""",
        )
        assertEquals("m", event?.message)
        assertEquals("t", event?.topic)
    }
}
