package to.sava.peranta.toast

import kotlin.test.Test
import kotlin.test.assertEquals

/** スワイプを離したときに消すか戻すかの判断を検証する。 */
class ToastSwipeTest {

    /** 深く動かしていれば、動かした向きへ送り出す。 */
    @Test
    fun dismissesWhenDraggedFarEnough() {
        assertEquals(1f, swipeDismissDirection(fraction = 0.25f, velocity = 0f))
        assertEquals(-1f, swipeDismissDirection(fraction = -0.25f, velocity = 0f))
    }

    /** 距離が足りなくても速く払っていれば、払った向きへ送り出す。 */
    @Test
    fun dismissesWhenFlickedFastEnough() {
        assertEquals(1f, swipeDismissDirection(fraction = 0.05f, velocity = 900f))
        assertEquals(-1f, swipeDismissDirection(fraction = -0.05f, velocity = -900f))
    }

    /** 浅くて遅ければ元の位置へ戻す。 */
    @Test
    fun returnsWhenShallowAndSlow() {
        assertEquals(0f, swipeDismissDirection(fraction = 0.1f, velocity = 200f))
    }

    /** 速度は直近サンプルの先頭と末尾の差から求める。 */
    @Test
    fun computesVelocityFromSamples() {
        assertEquals(1_000f, swipeVelocity(listOf(0L to 0, 100L to 100)))
        assertEquals(-1_000f, swipeVelocity(listOf(0L to 0, 100L to -100)))
    }

    /** サンプルが 1 点しかない・時刻が進んでいないときは 0 とする。 */
    @Test
    fun returnsZeroVelocityWithoutUsableSamples() {
        assertEquals(0f, swipeVelocity(listOf(0L to 0)))
        assertEquals(0f, swipeVelocity(listOf(5L to 0, 5L to 40)))
    }
}
