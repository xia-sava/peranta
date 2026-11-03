package to.sava.peranta.window

import co.touchlab.kermit.Logger
import java.awt.Container
import java.awt.Window
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import javax.swing.SwingUtilities
import org.jetbrains.skiko.SkiaLayer

/**
 * Skiko に描画のデバイスを作り直させる（§3.3）。
 *
 * Skiko は描画に失敗すると `RenderException` を受けて作り直す。その入口が `SkiaLayer` の `private` な
 * `redrawerManager` の `findNextWorkingRenderApi(recreation = true)` で、古いデバイスを捨ててから同じ描画 API で
 * 新しいデバイスを作る。作れなければ次の描画 API へ移る。**外から呼ぶ公開の API が無いため、リフレクションで呼ぶ。**
 *
 * `SkiaLayer` を外して付け直す方法は使えない。外すと Compose の `SurfaceSkiaLayerComponent` が自分を破棄済みとし、
 * 以後は再描画を求めても何も起きない。
 *
 * **Skiko を上げて名前や形が変わると呼べなくなる。** [isAvailable] が偽になり、`SkikoRendererResetTest` が落ちる。
 */
internal object SkikoRendererReset {

    private val log = Logger.withTag("GpuReset")

    private val recreation: Pair<Field, Method>? = try {
        val manager = SkiaLayer::class.java.getDeclaredField("redrawerManager").apply { isAccessible = true }
        val findNext = manager.type.getMethod("findNextWorkingRenderApi", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        manager to findNext
    } catch (unreachable: ReflectiveOperationException) {
        log.e(unreachable) { "cannot reach the renderer of Skiko" }
        null
    } catch (inaccessible: RuntimeException) {
        log.e(inaccessible) { "cannot reach the renderer of Skiko" }
        null
    }

    /** 作り直す入口に手が届くか。 */
    fun isAvailable(): Boolean = recreation != null

    /**
     * 開いている全てのウィンドウ（メインウィンドウとトースト）の `SkiaLayer` について、描画のデバイスを作り直させる。
     *
     * Skiko が求めるとおりイベントディスパッチスレッドで作り直し、終わるまで待って返る。古いデバイスを捨てるときは、
     * Skiko が自分の描画と排他を取る（`Direct3DRedrawer` の `drawLock`）。
     */
    fun recreateAll() = SwingUtilities.invokeAndWait {
        Window.getWindows().filter { it.isDisplayable }.flatMap(::skiaLayers).forEach(::recreate)
    }

    private fun recreate(layer: SkiaLayer) {
        val (manager, findNext) = recreation ?: return
        try {
            findNext.invoke(manager.get(layer), true)
        } catch (failed: InvocationTargetException) {
            log.e(failed.targetException) { "could not recreate the renderer after a GPU reset" }
            return
        }
        layer.needRender()
        log.i { "recreated the renderer after a GPU reset: ${layer.renderApi}" }
    }
}

private fun skiaLayers(container: Container): List<SkiaLayer> = container.components.flatMap {
    when (it) {
        is SkiaLayer -> listOf(it)
        is Container -> skiaLayers(it)
        else -> emptyList()
    }
}
