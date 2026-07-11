package to.sava.peranta.send

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedStreamItemsTest {

    /** 単数（ACTION_SEND）が有れば 1 件のリストにし、複数は無視する。 */
    @Test
    fun singleTakesPrecedence() {
        assertEquals(listOf("a"), sharedStreamItems(single = "a", multiple = listOf("b", "c")))
    }

    /** 単数が無ければ複数（ACTION_SEND_MULTIPLE）をそのまま返す。 */
    @Test
    fun multipleUsedWhenNoSingle() {
        assertEquals(listOf("b", "c"), sharedStreamItems(single = null, multiple = listOf("b", "c")))
    }

    /** どちらも無ければ空リスト。 */
    @Test
    fun emptyWhenNeither() {
        assertEquals(emptyList(), sharedStreamItems<String>(single = null, multiple = null))
    }
}
