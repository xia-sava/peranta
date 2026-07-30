package to.sava.peranta.window

import com.russhwolf.settings.Settings
import java.awt.Rectangle

/** ウィンドウの位置・大きさ・最大化状態（§11）。位置と寸法は dp で持つ。 */
data class WindowGeometry(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val maximized: Boolean,
)

/**
 * ウィンドウの見え方を設定ストアに覚える（§11）。この端末でだけ意味を持つ表示状態のため
 * ペアリングでは配らず、[to.sava.peranta.config.PerantaConfig] とは別に保存する。
 */
class WindowGeometryStore(private val settings: Settings) {

    /** 覚えている見え方。未保存または寸法が壊れているときは null（既定の大きさで開く）。 */
    fun load(): WindowGeometry? {
        val x = settings.getIntOrNull(KEY_X) ?: return null
        val y = settings.getIntOrNull(KEY_Y) ?: return null
        val width = settings.getIntOrNull(KEY_WIDTH)?.takeIf { it > 0 } ?: return null
        val height = settings.getIntOrNull(KEY_HEIGHT)?.takeIf { it > 0 } ?: return null
        return WindowGeometry(
            x = x,
            y = y,
            width = width,
            height = height,
            maximized = settings.getBoolean(KEY_MAXIMIZED, false),
        )
    }

    /**
     * 見え方を保存する。最大化中の位置・寸法は画面いっぱいの値になるため書き込まず、
     * 最大化を解いたときに戻る大きさとして直前の値を残す。
     */
    fun save(geometry: WindowGeometry) {
        settings.putBoolean(KEY_MAXIMIZED, geometry.maximized)
        if (geometry.maximized) return
        settings.putInt(KEY_X, geometry.x)
        settings.putInt(KEY_Y, geometry.y)
        settings.putInt(KEY_WIDTH, geometry.width)
        settings.putInt(KEY_HEIGHT, geometry.height)
    }

    private companion object {
        const val KEY_X = "windowX"
        const val KEY_Y = "windowY"
        const val KEY_WIDTH = "windowWidth"
        const val KEY_HEIGHT = "windowHeight"
        const val KEY_MAXIMIZED = "windowMaximized"
    }
}

/**
 * [bounds] がいずれかの画面に重なるか。モニタを外した・解像度を変えた等で覚えていた位置が
 * どの画面にも無くなったとき、見えない場所へ復元してしまうのを防ぐ判定に使う。
 * 少しでも重なっていれば掴んで動かせるため、完全に外れた場合だけ偽とする。
 */
fun isOnAnyScreen(bounds: Rectangle, screens: List<Rectangle>): Boolean =
    screens.any { it.intersects(bounds) }
