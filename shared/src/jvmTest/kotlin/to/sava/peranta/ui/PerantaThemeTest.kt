package to.sava.peranta.ui

import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PerantaThemeTest {

    /** Desktop（jvm）の [platformUiDensitySpec] は高密度の指定値を返す。 */
    @Test
    fun jvmSpecReturnsDesktopDensityValues() {
        val spec = platformUiDensitySpec()
        assertEquals(0.85f, spec.densityScale)
        assertEquals(1.06f, spec.fontScale)
        assertEquals(38.dp, spec.minInteractiveSize)
    }

    /** PerantaTheme 配下では、外側の density に対し構造は 0.85 倍・文字は 1.06 倍が掛かる。 */
    @Test
    fun themeScalesDensityAndFontScale() = runComposeUiTest {
        var outerDensity = 0f
        var outerFontScale = 0f
        var innerDensity = 0f
        var innerFontScale = 0f
        setContent {
            val outer = LocalDensity.current
            outerDensity = outer.density
            outerFontScale = outer.fontScale
            PerantaTheme {
                val inner = LocalDensity.current
                innerDensity = inner.density
                innerFontScale = inner.fontScale
                Text(text = "content")
            }
        }
        assertEquals(outerDensity * 0.85f, innerDensity)
        assertEquals(outerFontScale * 1.06f, innerFontScale)
    }

    /** PerantaTheme 配下では最小インタラクティブサイズが 38dp になる。 */
    @Test
    fun themeProvidesMinimumInteractiveSize() = runComposeUiTest {
        var minInteractiveSize: Dp = Dp.Unspecified
        setContent {
            PerantaTheme {
                minInteractiveSize = LocalMinimumInteractiveComponentSize.current
                Text(text = "content")
            }
        }
        assertEquals(38.dp, minInteractiveSize)
    }
}
