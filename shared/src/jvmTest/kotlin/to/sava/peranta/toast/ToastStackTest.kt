package to.sava.peranta.toast

import kotlin.test.Test
import kotlin.test.assertEquals

/** 画面右下へ積むトーストの縦位置の計算を検証する。 */
class ToastStackTest {

    private fun activeToast(id: String) =
        ActiveToast(ReceivedNotificationToast(id = id, title = "件名", body = "本文"))

    /** 何も出ていなければ画面端の余白だけで置く。 */
    @Test
    fun noOffsetForTheFirstToast() {
        assertEquals(0, stackedOffset(emptyList()))
    }

    /** 先に出たトーストの高さと間隔を足し上げる。 */
    @Test
    fun sumsHeightsAndGapsBelow() {
        assertEquals(108, stackedOffset(listOf(100)))
        assertEquals(224, stackedOffset(listOf(100, 108)))
    }

    /** 高さを報告していないトーストは間隔だけを占める。 */
    @Test
    fun countsGapForUnmeasuredToasts() {
        assertEquals(8, stackedOffset(listOf(0)))
    }

    /** 報告された高さを使い、先に出たトーストの分だけ新しいものを上へずらす。 */
    @Test
    fun offsetsByEarlierToasts() {
        val stack = ToastStack()
        val first = activeToast("a")
        val second = activeToast("b")
        stack.report(first, 100)
        stack.report(second, 120)
        val order = listOf(first, second)

        assertEquals(0, stack.offsetBelow(order, first))
        assertEquals(108, stack.offsetBelow(order, second))
    }

    /** 消えたトーストの高さは忘れる。 */
    @Test
    fun forgetsRemovedToasts() {
        val stack = ToastStack()
        val first = activeToast("a")
        val second = activeToast("b")
        stack.report(first, 100)
        stack.report(second, 120)

        stack.forget(first)

        assertEquals(8, stack.offsetBelow(listOf(first, second), second))
    }
}
