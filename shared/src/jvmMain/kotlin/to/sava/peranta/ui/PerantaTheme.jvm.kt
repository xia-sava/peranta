package to.sava.peranta.ui

import androidx.compose.ui.unit.dp

actual fun platformUiDensitySpec(): UiDensitySpec =
    UiDensitySpec(densityScale = 0.85f, fontScale = 1.06f, minInteractiveSize = 38.dp, drawerItemHeight = 44.dp)
