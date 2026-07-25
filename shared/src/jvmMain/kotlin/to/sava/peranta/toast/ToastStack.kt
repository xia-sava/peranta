package to.sava.peranta.toast

import androidx.compose.runtime.mutableStateMapOf

/** トースト同士の間隔（AWT のユーザ空間座標）。 */
internal const val TOAST_GAP = 8

/** 先に出たトーストが占める高さの合計（間隔込み）。 */
internal fun stackedOffset(heightsBelow: List<Int>): Int =
    heightsBelow.sumOf { height -> height + TOAST_GAP }

/**
 * 積み上げたトーストの縦位置。ウィンドウの実寸はレイアウトが済むまで分からないため、
 * 各ウィンドウが自分の高さを報告する。先に出たものが下に残り、新しいものが上へ積まれるので、
 * 既に出ているトーストの位置は動かない。
 */
internal class ToastStack {

    private val heights = mutableStateMapOf<ActiveToast, Int>()

    fun report(toast: ActiveToast, heightPx: Int) {
        heights[toast] = heightPx
    }

    fun forget(toast: ActiveToast) {
        heights.remove(toast)
    }

    /** [order] のうち [toast] より前に出たトーストが占める高さの合計。 */
    fun offsetBelow(order: List<ActiveToast>, toast: ActiveToast): Int =
        stackedOffset(order.takeWhile { it !== toast }.map { heights[it] ?: 0 })
}
