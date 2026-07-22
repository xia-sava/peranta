package to.sava.peranta.ui.shell

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PerantaShellTest {

    private fun drawerTag(destination: ShellDestination): String =
        "$TAG_SHELL_DRAWER_ITEM_PREFIX${destination.name}"

    @Composable
    private fun shell(
        destination: ShellDestination,
        showReceiveSetup: Boolean = true,
        onNavigate: (ShellDestination) -> Unit = {},
    ) {
        PerantaShell(
            destination = destination,
            onNavigate = onNavigate,
            serverLabel = "peranta.example.com",
            deviceLabel = "Pixel 9",
            showReceiveSetup = showReceiveSetup,
        ) { Text(text = "content-${it.name}") }
    }

    /** タイムライン表示ではハンバーガーを押すとドロワーが開く。 */
    @Test
    fun hamburgerOpensDrawer() = runComposeUiTest {
        setContent { shell(ShellDestination.Timeline) }
        onNodeWithTag(drawerTag(ShellDestination.Timeline)).assertIsNotDisplayed()
        onNodeWithTag(TAG_SHELL_MENU).performClick()
        onNodeWithTag(drawerTag(ShellDestination.Timeline)).assertIsDisplayed()
    }

    /** ドロワーには全行き先が並ぶ。 */
    @Test
    fun drawerListsAllDestinations() = runComposeUiTest {
        setContent { shell(ShellDestination.Timeline) }
        onNodeWithTag(TAG_SHELL_MENU).performClick()
        ShellDestination.entries.forEach { destination ->
            onNodeWithTag(drawerTag(destination)).assertExists()
        }
    }

    /** 受信のセットアップは showReceiveSetup が false のとき出さない。 */
    @Test
    fun receiveSetupHiddenWhenDisabled() = runComposeUiTest {
        setContent { shell(ShellDestination.Timeline, showReceiveSetup = false) }
        onNodeWithTag(TAG_SHELL_MENU).performClick()
        onAllNodesWithTag(drawerTag(ShellDestination.ReceiveSetup)).assertCountEquals(0)
        onNodeWithTag(drawerTag(ShellDestination.Settings)).assertExists()
    }

    /** 現在地はドロワーでハイライトし、他の項目は選択状態にならない。 */
    @Test
    fun currentDestinationIsHighlighted() = runComposeUiTest {
        setContent { shell(ShellDestination.Timeline) }
        onNodeWithTag(TAG_SHELL_MENU).performClick()
        onNodeWithTag(drawerTag(ShellDestination.Timeline)).assertIsSelected()
        onNodeWithTag(drawerTag(ShellDestination.Settings)).assertIsNotSelected()
    }

    /** サブ画面では戻る（←）が出てハンバーガーは出ず、押すとタイムラインへ遷移する。 */
    @Test
    fun subScreenShowsBackAndNavigatesToTimeline() = runComposeUiTest {
        var navigated: ShellDestination? = null
        setContent { shell(ShellDestination.Settings, onNavigate = { navigated = it }) }
        onAllNodesWithTag(TAG_SHELL_MENU).assertCountEquals(0)
        onNodeWithTag(TAG_SHELL_BACK).assertIsDisplayed()
        onNodeWithTag(TAG_SHELL_BACK).performClick()
        assertEquals(ShellDestination.Timeline, navigated)
    }

    /** ドロワー項目タップで onNavigate が呼ばれ、ドロワーが閉じる。 */
    @Test
    fun drawerItemNavigatesAndCloses() = runComposeUiTest {
        var navigated: ShellDestination? = null
        setContent { shell(ShellDestination.Timeline, onNavigate = { navigated = it }) }
        onNodeWithTag(TAG_SHELL_MENU).performClick()
        onNodeWithTag(drawerTag(ShellDestination.Settings)).performClick()
        assertEquals(ShellDestination.Settings, navigated)
        onNodeWithTag(drawerTag(ShellDestination.Timeline)).assertIsNotDisplayed()
    }
}
