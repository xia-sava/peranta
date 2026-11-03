package to.sava.peranta.window

import kotlin.test.Test
import kotlin.test.assertTrue

class SkikoRendererResetTest {

    /**
     * 今の Skiko に、描画のデバイスを作り直させる入口がある。
     *
     * Skiko を上げて `SkiaLayer.redrawerManager` や `findNextWorkingRenderApi` の名前や形が変わると、ここで落ちる。
     * 配布物では落ちずに GPU を使わない描画へ切り替わるため、気づけるのはここだけになる。
     */
    @Test
    fun `reaches the entry point that recreates the renderer`() {
        assertTrue(SkikoRendererReset.isAvailable())
    }
}
