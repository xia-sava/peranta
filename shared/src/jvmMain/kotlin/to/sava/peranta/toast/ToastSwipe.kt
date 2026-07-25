package to.sava.peranta.toast

import kotlin.math.abs
import kotlin.math.sign

/** 指を離したときに消すと判断する横移動量。トースト幅に対する割合で見る。 */
private const val SWIPE_DISMISS_FRACTION = 0.2f

/** 距離が足りなくても消すと判断する振り速度（AWT のユーザ空間座標／秒）。 */
private const val SWIPE_DISMISS_VELOCITY = 700f

/** 振り速度を測る直近の時間幅。 */
internal const val SWIPE_VELOCITY_WINDOW_MILLIS = 120L

/**
 * ドラッグを離したときにトーストを送り出す向き。0 なら元の位置へ戻す。
 * 深く動かしたか、浅くても速く払ったかのどちらかで消す。
 */
internal fun swipeDismissDirection(fraction: Float, velocity: Float): Float =
    when {
        abs(fraction) >= SWIPE_DISMISS_FRACTION -> sign(fraction)
        abs(velocity) >= SWIPE_DISMISS_VELOCITY -> sign(velocity)
        else -> 0f
    }

/** 直近の移動サンプル（時刻と移動量）から振り速度を求める。 */
internal fun swipeVelocity(samples: List<Pair<Long, Int>>): Float {
    if (samples.size < 2) return 0f
    val elapsed = samples.last().first - samples.first().first
    if (elapsed <= 0L) return 0f
    return (samples.last().second - samples.first().second) * 1_000f / elapsed
}
