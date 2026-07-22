package to.sava.peranta.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp

/**
 * プラットフォームごとの UI 密度指定。dp 構造・sp 文字・最小インタラクティブサイズを束ねる。
 */
data class UiDensitySpec(
    /** dp 指定の全寸法に掛ける倍率。 */
    val densityScale: Float,
    /** sp 指定（文字）に掛ける補正。文字の実効倍率は densityScale × fontScale。 */
    val fontScale: Float,
    /** Material 部品の最小インタラクティブターゲット。 */
    val minInteractiveSize: Dp,
    /** ドロワー項目の高さ。M3 既定は 56dp。 */
    val drawerItemHeight: Dp,
)

/** 実行中のプラットフォームの [UiDensitySpec] を返す。 */
expect fun platformUiDensitySpec(): UiDensitySpec

/** システムのライト/ダーク設定に追従する Material3 テーマ。プラットフォームごとの UI 密度も適用する。 */
@Composable
fun PerantaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val spec = platformUiDensitySpec()
    val base = LocalDensity.current
    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
    ) {
        CompositionLocalProvider(
            LocalDensity provides Density(base.density * spec.densityScale, base.fontScale * spec.fontScale),
            LocalMinimumInteractiveComponentSize provides spec.minInteractiveSize,
            content = content,
        )
    }
}
