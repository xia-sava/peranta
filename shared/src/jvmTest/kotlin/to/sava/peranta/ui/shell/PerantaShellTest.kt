package to.sava.peranta.ui.shell

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PerantaShellTest {

    private fun drawerTag(destination: ShellDestination): String =
        "$TAG_SHELL_DRAWER_ITEM_PREFIX${destination.name}"

    /**
     * ドロワーに並ぶ行き先。受信のセットアップ・動作チェック・接続設定と暗号キーの取り込みは
     * 設定画面のセットアップ状況から開くため含めない。
     */
    private val drawerDestinations = listOf(
        ShellDestination.Timeline,
        ShellDestination.AppFilter,
        ShellDestination.Settings,
    )

    /** 狭幅（開閉式ドロワー）を強制するサイズ。閾値未満。 */
    private val narrowShellModifier = Modifier.size(width = 400.dp, height = 800.dp)

    /** 広幅（常設ドロワー）を強制するサイズ。閾値以上。 */
    private val wideShellModifier = Modifier.size(width = 1000.dp, height = 800.dp)

    @Composable
    private fun shell(
        destination: ShellDestination,
        onNavigate: (ShellDestination) -> Unit = {},
        modifier: Modifier = narrowShellModifier,
    ) {
        PerantaShell(
            destination = destination,
            onNavigate = onNavigate,
            serverLabel = "peranta.example.com",
            deviceLabel = "Pixel 9",
            modifier = modifier,
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

    /** ドロワーにはナビゲーションの行き先が並ぶ（受信のセットアップと動作チェックは含まない）。 */
    @Test
    fun drawerListsNavigationDestinations() = runComposeUiTest {
        setContent { shell(ShellDestination.Timeline) }
        onNodeWithTag(TAG_SHELL_MENU).performClick()
        drawerDestinations.forEach { destination ->
            onNodeWithTag(drawerTag(destination)).assertExists()
        }
    }

    /**
     * 受信のセットアップ・動作チェック・接続設定と暗号キーの取り込みはドロワーに出さない
     * （入口は設定画面のセットアップ状況に集約）。
     */
    @Test
    fun receiveSetupHealthCheckAndPairingImportAbsentFromDrawer() = runComposeUiTest {
        setContent { shell(ShellDestination.Timeline) }
        onNodeWithTag(TAG_SHELL_MENU).performClick()
        onAllNodesWithTag(drawerTag(ShellDestination.ReceiveSetup)).assertCountEquals(0)
        onAllNodesWithTag(drawerTag(ShellDestination.HealthCheck)).assertCountEquals(0)
        onAllNodesWithTag(drawerTag(ShellDestination.PairingImport)).assertCountEquals(0)
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

    /** 広幅では開閉操作なしでドロワー項目が常時表示され、ハンバーガーは出ない。 */
    @Test
    fun wideShowsPermanentDrawerWithoutHamburger() = runComposeUiTest {
        setContent { shell(ShellDestination.Timeline, modifier = wideShellModifier) }
        drawerDestinations.forEach { destination ->
            onNodeWithTag(drawerTag(destination)).assertIsDisplayed()
        }
        onAllNodesWithTag(TAG_SHELL_MENU).assertCountEquals(0)
    }

    /** 広幅のサブ画面では戻る（←）もハンバーガーも出ない（現在地は常設一覧が示す）。 */
    @Test
    fun wideSubScreenShowsNoLeadingIcon() = runComposeUiTest {
        setContent { shell(ShellDestination.Settings, modifier = wideShellModifier) }
        onAllNodesWithTag(TAG_SHELL_BACK).assertCountEquals(0)
        onAllNodesWithTag(TAG_SHELL_MENU).assertCountEquals(0)
        onNodeWithTag(drawerTag(ShellDestination.Settings)).assertIsDisplayed()
    }

    /** 広幅では常設ドロワー項目のタップで onNavigate が呼ばれる。 */
    @Test
    fun wideDrawerItemNavigates() = runComposeUiTest {
        var navigated: ShellDestination? = null
        setContent {
            shell(ShellDestination.Timeline, onNavigate = { navigated = it }, modifier = wideShellModifier)
        }
        onNodeWithTag(drawerTag(ShellDestination.Settings)).performClick()
        assertEquals(ShellDestination.Settings, navigated)
    }

    /** 広幅でも現在地は常設ドロワーでハイライトされる。 */
    @Test
    fun wideCurrentDestinationIsHighlighted() = runComposeUiTest {
        setContent { shell(ShellDestination.Settings, modifier = wideShellModifier) }
        onNodeWithTag(drawerTag(ShellDestination.Settings)).assertIsSelected()
        onNodeWithTag(drawerTag(ShellDestination.Timeline)).assertIsNotSelected()
    }

    /** 広幅シェルの常設ドロワーシートは [DRAWER_SHEET_WIDTH]（280dp）幅である。 */
    @Test
    fun wideDrawerSheetHasFixedWidth() = runComposeUiTest {
        setContent { shell(ShellDestination.Timeline, modifier = wideShellModifier) }
        onNodeWithTag(TAG_SHELL_DRAWER_SHEET).assertWidthIsEqualTo(DRAWER_SHEET_WIDTH)
    }

    /** 閾値ちょうどの幅は広幅（常設ドロワー）になる。 */
    @Test
    fun exactThresholdWidthIsWide() = runComposeUiTest {
        setContent {
            shell(
                ShellDestination.Timeline,
                modifier = Modifier.size(width = WIDE_LAYOUT_MIN_WIDTH, height = 800.dp),
            )
        }
        onNodeWithTag(drawerTag(ShellDestination.Timeline)).assertIsDisplayed()
        onAllNodesWithTag(TAG_SHELL_MENU).assertCountEquals(0)
    }

    /** 閾値を 1dp でも下回る幅は狭幅（開閉式ドロワー）になる。 */
    @Test
    fun justBelowThresholdWidthIsNarrow() = runComposeUiTest {
        setContent {
            shell(
                ShellDestination.Timeline,
                modifier = Modifier.size(width = WIDE_LAYOUT_MIN_WIDTH - 1.dp, height = 800.dp),
            )
        }
        onNodeWithTag(drawerTag(ShellDestination.Timeline)).assertIsNotDisplayed()
        onNodeWithTag(TAG_SHELL_MENU).assertExists()
    }
}
