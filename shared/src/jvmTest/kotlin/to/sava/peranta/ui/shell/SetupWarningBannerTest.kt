package to.sava.peranta.ui.shell

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SetupWarningBannerTest {

    /** バナーは未達の告知文言と確認導線の文言を表示する。 */
    @Test
    fun bannerShowsMessageAndAction() = runComposeUiTest {
        setContent { SetupWarningBanner(onConfirm = {}) }
        onNodeWithTag(TAG_SETUP_WARNING_BANNER).assertIsDisplayed()
        onNodeWithText("セットアップに未達があります").assertIsDisplayed()
        onNodeWithText("確認する").assertIsDisplayed()
    }

    /** バナー全体がタップ対象で、タップすると onConfirm が呼ばれる。 */
    @Test
    fun bannerClickInvokesOnConfirm() = runComposeUiTest {
        var confirmed = 0
        setContent { SetupWarningBanner(onConfirm = { confirmed++ }) }
        onNodeWithTag(TAG_SETUP_WARNING_BANNER).performClick()
        assertEquals(1, confirmed)
    }
}
