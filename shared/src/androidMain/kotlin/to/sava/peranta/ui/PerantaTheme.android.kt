package to.sava.peranta.ui

import androidx.compose.ui.unit.dp

actual fun platformUiDensitySpec(): UiDensitySpec =
    UiDensitySpec(densityScale = 1f, fontScale = 1f, minInteractiveSize = 48.dp, drawerItemHeight = 56.dp)
