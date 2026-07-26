package to.sava.peranta.window

import com.russhwolf.settings.MapSettings
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowGeometryStoreTest {

    private fun geometry(
        x: Int = 100,
        y: Int = 50,
        width: Int = 1000,
        height: Int = 720,
        maximized: Boolean = false,
    ) = WindowGeometry(x = x, y = y, width = width, height = height, maximized = maximized)

    /** 保存した見え方はそのまま読み戻せる。 */
    @Test
    fun savedGeometryRoundTrips() {
        val store = WindowGeometryStore(MapSettings())
        val saved = geometry()
        store.save(saved)
        assertEquals(saved, store.load())
    }

    /** 未保存なら null を返す（既定の大きさで開く）。 */
    @Test
    fun emptyStoreHasNoGeometry() {
        assertNull(WindowGeometryStore(MapSettings()).load())
    }

    /** 最大化中の保存は位置・寸法を書き換えず、最大化を解いたときに戻る大きさを残す。 */
    @Test
    fun maximizedSaveKeepsRestoredSize() {
        val store = WindowGeometryStore(MapSettings())
        store.save(geometry(x = 100, y = 50, width = 1000, height = 720))
        store.save(geometry(x = 0, y = 0, width = 2560, height = 1440, maximized = true))

        val loaded = store.load()
        assertEquals(geometry(x = 100, y = 50, width = 1000, height = 720, maximized = true), loaded)
    }

    /** 寸法が壊れている（0 以下）記録は無かったものとして扱う。 */
    @Test
    fun nonPositiveSizeIsIgnored() {
        val settings = MapSettings()
        WindowGeometryStore(settings).save(geometry())
        settings.putInt("windowWidth", 0)
        assertNull(WindowGeometryStore(settings).load())
    }

    /** 画面に重なっていれば復元してよい。 */
    @Test
    fun boundsOverlappingScreenAreOnScreen() {
        val screens = listOf(Rectangle(0, 0, 1920, 1080))
        assertTrue(isOnAnyScreen(Rectangle(1800, 900, 1000, 720), screens))
    }

    /** どの画面にも重ならない位置は復元しない（モニタを外した後の座標）。 */
    @Test
    fun boundsOutsideEveryScreenAreOffScreen() {
        val screens = listOf(Rectangle(0, 0, 1920, 1080))
        assertFalse(isOnAnyScreen(Rectangle(3000, 200, 1000, 720), screens))
    }

    /** 複数画面ではいずれかに重なっていればよい（副モニタ上のウィンドウ）。 */
    @Test
    fun boundsOnSecondaryScreenAreOnScreen() {
        val screens = listOf(Rectangle(0, 0, 1920, 1080), Rectangle(1920, 0, 2560, 1440))
        assertTrue(isOnAnyScreen(Rectangle(2200, 300, 1000, 720), screens))
    }
}
