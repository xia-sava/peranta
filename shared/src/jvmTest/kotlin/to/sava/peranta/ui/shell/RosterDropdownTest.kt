package to.sava.peranta.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.roster.RosterEntry
import to.sava.peranta.roster.RosterFetchResult

@OptIn(ExperimentalTestApi::class)
class RosterDropdownTest {

    private fun entry(deviceId: String, deviceName: String, lastUpdatedEpochMillis: Long = nowEpochMillis()) =
        RosterEntry(
            deviceId = deviceId,
            deviceName = deviceName,
            endpoint = "https://h/$deviceId",
            capabilities = emptyList(),
            sender = false,
            lastUpdatedEpochMillis = lastUpdatedEpochMillis,
        )

    private fun itemTag(deviceId: String) = "$TAG_ROSTER_ITEM_PREFIX$deviceId"

    /** RosterDropdown をコンテナに置いて描画する。 */
    @Composable
    private fun host(roster: RosterUi) {
        Box(modifier = Modifier.size(600.dp)) {
            RosterDropdown(roster, modifier = Modifier.align(Alignment.TopStart))
        }
    }

    /** トグルをクリックすると fetch が呼ばれ、取得できた端末名と相対時刻の行が表示される。 */
    @Test
    fun toggleClickFetchesAndShowsRows() = runComposeUiTest {
        var fetchCount = 0
        val roster = RosterUi(
            selfDeviceId = null,
            fetch = {
                fetchCount++
                RosterFetchResult.Fetched(listOf(entry("dev-a", "Alpha")))
            },
        )
        setContent { host(roster) }
        onNodeWithTag(TAG_ROSTER_TOGGLE).performClick()
        onNodeWithTag(itemTag("dev-a")).assertIsDisplayed()
        assertEquals(1, fetchCount)
        onNodeWithText("Alpha").assertIsDisplayed()
        onNodeWithText("たった今").assertIsDisplayed()
    }

    /** 自端末の行にだけ「（この端末）」が付き、他端末には付かない。 */
    @Test
    fun selfDeviceRowIsMarked() = runComposeUiTest {
        val roster = RosterUi(
            selfDeviceId = "dev-a",
            fetch = { RosterFetchResult.Fetched(listOf(entry("dev-a", "Alpha"), entry("dev-b", "Bravo"))) },
        )
        setContent { host(roster) }
        onNodeWithTag(TAG_ROSTER_TOGGLE).performClick()
        onNodeWithText("Alpha（この端末）").assertIsDisplayed()
        onNodeWithText("Bravo").assertIsDisplayed()
    }

    /** fetch が FetchFailed を返すとエラー表示になる。 */
    @Test
    fun fetchFailureShowsError() = runComposeUiTest {
        val roster = RosterUi(selfDeviceId = null, fetch = { RosterFetchResult.FetchFailed })
        setContent { host(roster) }
        onNodeWithTag(TAG_ROSTER_TOGGLE).performClick()
        onNodeWithTag(TAG_ROSTER_ERROR).assertIsDisplayed()
    }

    /** fetch が成功して 0 件なら空表示になる。 */
    @Test
    fun emptyResultShowsEmptyMessage() = runComposeUiTest {
        val roster = RosterUi(selfDeviceId = null, fetch = { RosterFetchResult.Fetched(emptyList()) })
        setContent { host(roster) }
        onNodeWithTag(TAG_ROSTER_TOGGLE).performClick()
        onNodeWithTag(TAG_ROSTER_EMPTY).assertIsDisplayed()
    }

    /** fetch が完了しない間は取得中表示になる。 */
    @Test
    fun pendingFetchShowsLoading() = runComposeUiTest {
        val roster = RosterUi(selfDeviceId = null, fetch = { awaitCancellation() })
        setContent { host(roster) }
        onNodeWithTag(TAG_ROSTER_TOGGLE).performClick()
        onNodeWithTag(TAG_ROSTER_LOADING).assertIsDisplayed()
    }

    /** 一度閉じて再度開くと fetch が再度呼ばれる（キャッシュしない）。 */
    @Test
    fun reopeningFetchesAgain() = runComposeUiTest {
        var fetchCount = 0
        val roster = RosterUi(
            selfDeviceId = null,
            fetch = {
                fetchCount++
                RosterFetchResult.Fetched(emptyList())
            },
        )
        setContent { host(roster) }
        onNodeWithTag(TAG_ROSTER_TOGGLE).performClick()
        onNodeWithTag(TAG_ROSTER_EMPTY).assertIsDisplayed()
        // メニュー行の外（Popup 自身のウィンドウ内でメニュー本体より遠い位置）をクリックして、
        // 外側クリックでの dismiss（DropdownMenu 既定の dismissOnClickOutside）を起こす。
        onNodeWithTag(TAG_ROSTER_EMPTY).performTouchInput { click(Offset(500f, 500f)) }
        onNodeWithTag(TAG_ROSTER_EMPTY).assertDoesNotExist()
        onNodeWithTag(TAG_ROSTER_TOGGLE).performClick()
        onNodeWithTag(TAG_ROSTER_EMPTY).assertIsDisplayed()
        assertEquals(2, fetchCount)
    }

    /** 端末行はクリックアクションを持たない（表示専用で失効等の操作は付けない）。 */
    @Test
    fun rowHasNoClickAction() = runComposeUiTest {
        val roster = RosterUi(
            selfDeviceId = null,
            fetch = { RosterFetchResult.Fetched(listOf(entry("dev-a", "Alpha"))) },
        )
        setContent { host(roster) }
        onNodeWithTag(TAG_ROSTER_TOGGLE).performClick()
        onNodeWithTag(itemTag("dev-a")).assert(hasNoClickAction())
    }
}
