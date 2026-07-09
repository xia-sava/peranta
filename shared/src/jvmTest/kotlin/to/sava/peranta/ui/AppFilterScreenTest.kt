package to.sava.peranta.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.filter.FilterMode
import to.sava.peranta.filter.RuleAction
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Priority
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AppFilterScreenTest {

    private fun repository(config: PerantaConfig = PerantaConfig()): ConfigRepository =
        ConfigRepository(MapSettings()).also { it.save(config) }

    private class FakeInstalledAppsProvider(
        private val apps: List<InstalledApp>,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : InstalledAppsProvider {
        override suspend fun loadInstalledApps(): List<InstalledApp> {
            gate?.await()
            return apps
        }
    }

    private fun app(packageName: String, system: Boolean = false) =
        InstalledApp(packageName = packageName, label = packageName, isSystemApp = system)

    /** アプリ一覧の解決前はローディングインジケータを表示する。 */
    @Test
    fun showsLoadingUntilAppsResolved() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        setContent {
            AppFilterScreen(
                controller = AppFilterController(repository()),
                installedAppsProvider = FakeInstalledAppsProvider(listOf(app("com.a")), gate),
            )
        }
        onNodeWithTag(TAG_APP_FILTER_LOADING).assertIsDisplayed()
    }

    /** denylist で通常アプリにチェックを入れると EXCLUDE ルールが永続化される。 */
    @Test
    fun denylistCheckingNormalAppPersistsExclude() = runComposeUiTest {
        val repo = repository()
        setContent {
            AppFilterScreen(
                controller = AppFilterController(repo),
                installedAppsProvider = FakeInstalledAppsProvider(listOf(app("com.chat"))),
            )
        }
        onNodeWithTag("$TAG_APP_FILTER_CHECKBOX_PREFIX${"com.chat"}").performClick()
        val rules = repo.load().filterRules
        assertEquals(1, rules.size)
        assertEquals("com.chat", rules[0].packageName)
        assertEquals(RuleAction.EXCLUDE, rules[0].action)
    }

    /** allowlist ではチェック＝許可のため、チェックで INCLUDE ルールが永続化される。 */
    @Test
    fun allowlistCheckingNormalAppPersistsInclude() = runComposeUiTest {
        val repo = repository(PerantaConfig(filterMode = FilterMode.ALLOWLIST))
        setContent {
            AppFilterScreen(
                controller = AppFilterController(repo),
                installedAppsProvider = FakeInstalledAppsProvider(listOf(app("com.chat"))),
            )
        }
        onNodeWithTag("$TAG_APP_FILTER_CHECKBOX_PREFIX${"com.chat"}").performClick()
        val rules = repo.load().filterRules
        assertEquals(RuleAction.INCLUDE, rules.single().action)
    }

    /** システムアプリは既定で折りたたまれ、展開すると個別行が現れる。 */
    @Test
    fun systemAppsCollapsedByDefaultAndExpandable() = runComposeUiTest {
        setContent {
            AppFilterScreen(
                controller = AppFilterController(repository()),
                installedAppsProvider = FakeInstalledAppsProvider(
                    listOf(app("com.chat"), app("com.sys", system = true)),
                ),
            )
        }
        onNodeWithTag(TAG_APP_FILTER_SYSTEM_HEADER).assertIsDisplayed()
        onAllNodesWithTag("$TAG_APP_FILTER_CHECKBOX_PREFIX${"com.sys"}").assertCountEquals(0)
        onNodeWithTag(TAG_APP_FILTER_SYSTEM_HEADER).performClick()
        onNodeWithTag("$TAG_APP_FILTER_CHECKBOX_PREFIX${"com.sys"}").assertIsDisplayed()
    }

    /** システムグループの TriState を押すと（既定は全除外）全メンバが INCLUDE で復帰する。 */
    @Test
    fun systemGroupTriStateUnchecksAll() = runComposeUiTest {
        val repo = repository()
        setContent {
            AppFilterScreen(
                controller = AppFilterController(repo),
                installedAppsProvider = FakeInstalledAppsProvider(
                    listOf(app("com.sys1", system = true), app("com.sys2", system = true)),
                ),
            )
        }
        onNodeWithTag(TAG_APP_FILTER_SYSTEM_TRISTATE).performClick()
        val included = repo.load().filterRules.filter { it.action == RuleAction.INCLUDE }.map { it.packageName }.toSet()
        assertEquals(setOf("com.sys1", "com.sys2"), included)
    }

    /** アプリ名タップで詳細画面が開き、伏せ字を設定して保存するとルールへ反映される。 */
    @Test
    fun detailDialogSavesRedaction() = runComposeUiTest {
        val repo = repository()
        setContent {
            AppFilterScreen(
                controller = AppFilterController(repo),
                installedAppsProvider = FakeInstalledAppsProvider(listOf(app("com.bank"))),
            )
        }
        onNodeWithTag("$TAG_APP_FILTER_LABEL_PREFIX${"com.bank"}").performClick()
        onNodeWithTag(TAG_APP_FILTER_DETAIL_REDACT).performClick()
        onNodeWithTag("${TAG_APP_FILTER_DETAIL_PRIORITY_PREFIX}HIGH").performClick()
        onNodeWithTag(TAG_APP_FILTER_DETAIL_SAVE).performClick()
        val rule = repo.load().filterRules.single()
        assertEquals("com.bank", rule.packageName)
        assertTrue(rule.redact)
        assertEquals(Priority.HIGH, rule.priorityOverride)
    }

    /** 受信専用ロールでは履歴のパッケージをチェックすると、ローカルミラーへ除外を反映し送信元へ通知する。 */
    @Test
    fun receiveOnlyTogglePersistsMirrorAndSendsCommand() = runComposeUiTest {
        val repo = repository()
        var command: Triple<String, String, Boolean>? = null
        val controller = AppFilterController(
            repository = repo,
            commandScope = CoroutineScope(Dispatchers.Unconfined),
            sendMuteCommand = { packageName, senderDeviceId, mute -> command = Triple(packageName, senderDeviceId, mute) },
        )
        val payload = NotificationPayload(
            id = "n1", from = "phone", to = "*", sentAtEpochMillis = 1L,
            packageName = "com.spam", appName = "Spam", title = "t", text = "x",
            notificationKey = "k", postedAtEpochMillis = 1L,
        )
        val items = MutableStateFlow<List<TimelineItem>>(
            listOf(ReceivedNotification(id = "n1", timestampEpochMillis = 1L, payload = payload)),
        )
        setContent { AppFilterScreen(controller = controller, items = items) }

        onNodeWithTag("$TAG_APP_FILTER_CHECKBOX_PREFIX${"com.spam"}").performClick()
        assertEquals(RuleAction.EXCLUDE, repo.load().filterRules.single().action)
        assertEquals(Triple("com.spam", "phone", true), command)
    }

    /** 受信専用ロールで履歴が空なら候補は出ず、案内文だけ出す。 */
    @Test
    fun receiveOnlyEmptyHistoryShowsGuidance() = runComposeUiTest {
        val items = MutableStateFlow<List<TimelineItem>>(emptyList())
        setContent { AppFilterScreen(controller = AppFilterController(repository()), items = items) }
        onNodeWithTag(TAG_APP_FILTER_MODE).assertIsDisplayed()
        onNodeWithText("履歴にアプリがありません。通知を受信すると候補が表示されます。").assertIsDisplayed()
    }
}
