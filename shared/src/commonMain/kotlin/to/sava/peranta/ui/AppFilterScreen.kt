package to.sava.peranta.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import to.sava.peranta.filter.appRuleSettingsFor
import to.sava.peranta.filter.applyAppRule
import to.sava.peranta.model.AppRuleSettings
import to.sava.peranta.filter.FilterMode
import to.sava.peranta.filter.FilterRule
import to.sava.peranta.filter.GroupCheckState
import to.sava.peranta.filter.groupCheckState
import to.sava.peranta.filter.isPackageChecked
import to.sava.peranta.filter.setGroupChecked
import to.sava.peranta.filter.setPackageChecked
import to.sava.peranta.filter.updatePackageDetail
import to.sava.peranta.model.Priority
import to.sava.peranta.timeline.TimelineItem

/** 画面見出しのタグ。 */
const val TAG_APP_FILTER_TITLE: String = "app-filter-title"

/** フィルタモード表示のタグ。 */
const val TAG_APP_FILTER_MODE: String = "app-filter-mode"

/** インストール済み一覧のロード中インジケータのタグ。 */
const val TAG_APP_FILTER_LOADING: String = "app-filter-loading"

/** アプリ行のチェックボックスのタグ接頭辞（末尾にパッケージ名を付ける）。 */
const val TAG_APP_FILTER_CHECKBOX_PREFIX: String = "app-filter-checkbox-"

/** アプリ名（詳細画面を開く導線）のタグ接頭辞（末尾にパッケージ名を付ける）。 */
const val TAG_APP_FILTER_LABEL_PREFIX: String = "app-filter-label-"

/** システムアプリ折りたたみグループの見出し（開閉トグル）のタグ。 */
const val TAG_APP_FILTER_SYSTEM_HEADER: String = "app-filter-system-header"

/** システムアプリグループの一括 TriState チェックボックスのタグ。 */
const val TAG_APP_FILTER_SYSTEM_TRISTATE: String = "app-filter-system-tristate"

/** 詳細画面の伏せ字トグルのタグ。 */
const val TAG_APP_FILTER_DETAIL_REDACT: String = "app-filter-detail-redact"

/** 詳細設定の「払いのけたら元の通知も消す」チェックのタグ。 */
const val TAG_APP_FILTER_DETAIL_SWIPE_DISMISS: String = "app-filter-detail-swipe-dismiss"

/** 詳細を開く 3 点ボタンのタグ接頭辞（末尾にパッケージ名を付ける）。 */
const val TAG_APP_FILTER_MENU_PREFIX: String = "app-filter-menu-"

/** 詳細を開く 3 点ボタンの説明。 */
const val DETAIL_MENU_DESCRIPTION: String = "このアプリの詳細な扱いを開く"

/** 受信専用ロールの、払いのけの扱いを切り替えるトグルのタグ接頭辞。 */
const val TAG_APP_FILTER_SWIPE_PREFIX: String = "app-filter-swipe-"

/** 通知を止めるトグルの説明。 */
const val MUTE_TOGGLE_DESCRIPTION: String = "このアプリの通知を送らせない"

/** 払いのけの扱いを切り替えるトグルの説明。 */
const val SWIPE_DISMISS_TOGGLE_DESCRIPTION: String = "払いのけたら発信側の通知も消す"

/** 詳細画面の優先度ラジオのタグ接頭辞（末尾に優先度名または "default" を付ける）。 */
const val TAG_APP_FILTER_DETAIL_PRIORITY_PREFIX: String = "app-filter-detail-priority-"

/** 詳細画面の保存ボタンのタグ。 */
const val TAG_APP_FILTER_DETAIL_SAVE: String = "app-filter-detail-save"

/** 優先度上書きの選択肢（既定＝上書きなし）。 */
private val PRIORITY_CHOICES: List<Pair<String, Priority?>> = listOf(
    "既定" to null,
    "低" to Priority.LOW,
    "標準" to Priority.NORMAL,
    "高" to Priority.HIGH,
)

/** 優先度上書きの選択肢を安定したタグ片へ写す。 */
private fun priorityTag(priority: Priority?): String = priority?.name ?: "default"

/**
 * アプリフィルタ画面（§10.4 / §7）。
 * [installedAppsProvider] が渡ると送信ロールのフル機能（インストール済み一覧・詳細設定）を、
 * 渡らないときは受信専用ロール（タイムライン履歴由来の候補を mute/unmute するだけ）を表示する。
 * チェックの意味は現在の [FilterMode] に応じて反転する（denylist=除外 / allowlist=許可）。
 * [showHeader] が false のときは画面見出し行（タイトルと「戻る」）を出さない。外側のアプリバーが
 * 見出しと戻る導線を持つ埋め込み利用で使い、既定の true では従来どおり見出しつきの単独画面として振る舞う。
 * 受信専用ロールのリストのスクロールバーはプラットフォーム依存のため [lazyScrollbarContent] スロットで注入する。
 */
@Composable
fun AppFilterScreen(
    controller: AppFilterController,
    modifier: Modifier = Modifier,
    installedAppsProvider: InstalledAppsProvider? = null,
    items: StateFlow<List<TimelineItem>>? = null,
    onBack: (() -> Unit)? = null,
    showHeader: Boolean = true,
    lazyScrollbarContent: @Composable BoxScope.(listState: LazyListState) -> Unit = {},
) {
    val mode = remember { controller.load().filterMode }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            ScreenHeader(
                mode = mode,
                receiveOnly = installedAppsProvider == null,
                onBack = onBack,
                showHeader = showHeader,
            )
            if (installedAppsProvider != null) {
                SendRoleContent(controller = controller, mode = mode, provider = installedAppsProvider)
            } else {
                ReceiveRoleContent(controller = controller, items = items, lazyScrollbarContent = lazyScrollbarContent)
            }
        }
    }
}

@Composable
private fun ScreenHeader(mode: FilterMode, receiveOnly: Boolean, onBack: (() -> Unit)?, showHeader: Boolean) {
    if (showHeader) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "アプリフィルタ",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag(TAG_APP_FILTER_TITLE),
            )
            if (onBack != null) {
                TextButton(onClick = onBack) { Text(text = "戻る") }
            }
        }
    }
    Text(
        text = modeDescription(mode, receiveOnly),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(TAG_APP_FILTER_MODE).padding(vertical = 8.dp),
    )
}

/**
 * 現在のモードと操作の意味を説明する文言（§10.4）。
 * この画面の設定はどれも通知を出す側（発信側）の端末に効く。受信端末から操作したときも
 * コマンドで発信側へ届いて発信側の設定が変わるため、そのことを常に明示する。
 */
private fun modeDescription(mode: FilterMode, receiveOnly: Boolean): String {
    val base = when (mode) {
        FilterMode.DENYLIST -> "除外リスト: 選んだアプリは転送しません"
        FilterMode.ALLOWLIST -> "許可リスト: 選んだアプリだけ転送します"
    }
    return if (receiveOnly) "$base。ここでの操作は発信側の端末の設定を変えます" else base
}

@Composable
private fun SendRoleContent(
    controller: AppFilterController,
    mode: FilterMode,
    provider: InstalledAppsProvider,
) {
    var rules by remember { mutableStateOf(controller.load().filterRules) }
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var systemExpanded by remember { mutableStateOf(false) }
    var detailApp by remember { mutableStateOf<InstalledApp?>(null) }

    LaunchedEffect(Unit) {
        apps = provider.loadInstalledApps()
    }

    val loaded = apps
    if (loaded == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.testTag(TAG_APP_FILTER_LOADING))
        }
        return
    }

    val normalApps = loaded.filterNot { it.isSystemApp }
    val systemApps = loaded.filter { it.isSystemApp }
    val systemPackageNames = systemApps.map { it.packageName }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(normalApps, key = { it.packageName }) { app ->
            AppRow(
                app = app,
                checked = isPackageChecked(rules, app.packageName, mode, app.isSystemApp),
                onCheckedChange = { checked ->
                    rules = controller.updateRules { current ->
                        setPackageChecked(current, app.packageName, checked, mode, app.isSystemApp)
                    }
                },
                onOpenDetail = { detailApp = app },
            )
        }
        if (systemApps.isNotEmpty()) {
            item(key = "system-header") {
                SystemGroupHeader(
                    expanded = systemExpanded,
                    state = groupCheckState(systemPackageNames, rules, mode) { pkg -> pkg in systemPackageNames },
                    onToggleExpanded = { systemExpanded = !systemExpanded },
                    onToggleAll = { checked ->
                        rules = controller.updateRules { current ->
                            setGroupChecked(current, systemPackageNames, checked, mode) { pkg -> pkg in systemPackageNames }
                        }
                    },
                )
            }
            if (systemExpanded) {
                items(systemApps, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        checked = isPackageChecked(rules, app.packageName, mode, app.isSystemApp),
                        onCheckedChange = { checked ->
                            rules = controller.updateRules { current ->
                                setPackageChecked(current, app.packageName, checked, mode, app.isSystemApp)
                            }
                        },
                        onOpenDetail = { detailApp = app },
                    )
                }
            }
        }
    }

    detailApp?.let { app ->
        DetailDialog(
            label = app.label,
            settings = appRuleSettingsFor(rules, app.packageName, mode, app.isSystemApp),
            onDismiss = { detailApp = null },
            onSave = { settings ->
                rules = controller.updateRules { current ->
                    applyAppRule(current, app.packageName, settings, mode, app.isSystemApp)
                }
                detailApp = null
            },
        )
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onOpenDetail: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        app.icon?.let { icon ->
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(32.dp).padding(end = 8.dp))
        }
        Text(
            text = app.label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpenDetail)
                .testTag("$TAG_APP_FILTER_LABEL_PREFIX${app.packageName}")
                .padding(vertical = 12.dp),
        )
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag("$TAG_APP_FILTER_CHECKBOX_PREFIX${app.packageName}"),
        )
        DetailMenuButton(app.packageName, onOpenDetail)
    }
}

@Composable
private fun SystemGroupHeader(
    expanded: Boolean,
    state: GroupCheckState,
    onToggleExpanded: () -> Unit,
    onToggleAll: (Boolean) -> Unit,
) {
    HorizontalDivider()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (expanded) "システムアプリ ▼" else "システムアプリ ▶",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onToggleExpanded)
                .testTag(TAG_APP_FILTER_SYSTEM_HEADER)
                .padding(vertical = 12.dp),
        )
        TriStateCheckbox(
            state = state.toToggleableState(),
            onClick = { onToggleAll(state != GroupCheckState.ALL_CHECKED) },
            modifier = Modifier.testTag(TAG_APP_FILTER_SYSTEM_TRISTATE),
        )
    }
}

private fun GroupCheckState.toToggleableState(): ToggleableState = when (this) {
    GroupCheckState.ALL_CHECKED -> ToggleableState.On
    GroupCheckState.NONE_CHECKED -> ToggleableState.Off
    GroupCheckState.PARTIALLY_CHECKED -> ToggleableState.Indeterminate
}

@Composable
private fun DetailDialog(
    label: String,
    settings: AppRuleSettings,
    onDismiss: () -> Unit,
    onSave: (settings: AppRuleSettings) -> Unit,
) {
    var priorityOverride by remember(label) { mutableStateOf(settings.priorityOverride) }
    var redact by remember(label) { mutableStateOf(settings.redact) }
    var swipeDismissesSource by remember(label) { mutableStateOf(settings.swipeDismissesSource) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "優先度の上書き", style = MaterialTheme.typography.labelMedium)
                PRIORITY_CHOICES.forEach { (label, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { priorityOverride = value }
                            .testTag("$TAG_APP_FILTER_DETAIL_PRIORITY_PREFIX${priorityTag(value)}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = priorityOverride == value, onClick = { priorityOverride = value })
                        Text(text = label)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = redact,
                        onCheckedChange = { redact = it },
                        modifier = Modifier.testTag(TAG_APP_FILTER_DETAIL_REDACT),
                    )
                    Text(text = "タイトル・本文を伏せる")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = swipeDismissesSource,
                        onCheckedChange = { swipeDismissesSource = it },
                        modifier = Modifier.testTag(TAG_APP_FILTER_DETAIL_SWIPE_DISMISS),
                    )
                    Text(text = "受信側で払いのけたらこの端末の通知も消す")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        settings.copy(
                            priorityOverride = priorityOverride,
                            redact = redact,
                            swipeDismissesSource = swipeDismissesSource,
                        ),
                    )
                },
                modifier = Modifier.testTag(TAG_APP_FILTER_DETAIL_SAVE),
            ) {
                Text(text = "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "やめる") }
        },
    )
}

/** トグル列が画面端に貼り付かないよう、行の右に置く余白。 */
private val TOGGLE_ROW_END_PADDING = 8.dp

/**
 * アイコントグルの意味を示す凡例（§10.4）。図柄だけでは何の設定か分からないため、
 * 一覧の上に一度だけ並びと同じ順で示す。
 */
@Composable
private fun ToggleLegend() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem(Icons.Default.NotificationsOff, MUTE_TOGGLE_DESCRIPTION)
        LegendItem(Icons.Default.ClearAll, SWIPE_DISMISS_TOGGLE_DESCRIPTION)
    }
}

@Composable
private fun LegendItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(
        modifier = Modifier.padding(start = 12.dp, end = TOGGLE_ROW_END_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * 詳細な扱いを開く 3 点ボタン（§10.4）。行のどこかを押せば開くことは見て取れないため、
 * 開く場所を図柄で示す。
 */
@Composable
private fun DetailMenuButton(packageName: String, onClick: () -> Unit) {
    Icon(
        imageVector = Icons.Default.MoreVert,
        contentDescription = DETAIL_MENU_DESCRIPTION,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(8.dp)
            .testTag("$TAG_APP_FILTER_MENU_PREFIX$packageName"),
    )
}

/**
 * 発信側の設定を切り替えるアイコントグル（§10.4）。入り切りは色の濃さで示す。
 */
@Composable
private fun IconToggle(
    on: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = if (on) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        },
        modifier = Modifier
            .clickable { onChange(!on) }
            .padding(8.dp)
            .testTag(tag),
    )
}

@Composable
private fun ReceiveRoleContent(
    controller: AppFilterController,
    items: StateFlow<List<TimelineItem>>?,
    lazyScrollbarContent: @Composable BoxScope.(listState: LazyListState) -> Unit,
) {
    val timeline = items?.collectAsState()?.value ?: emptyList()
    val candidates = remember(timeline) { historyPackagesFrom(timeline) }
    var rules by remember { mutableStateOf(controller.load().filterRules) }
    var detailCandidate by remember { mutableStateOf<HistoryPackage?>(null) }

    if (candidates.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "履歴にアプリがありません。通知を受信すると候補が表示されます。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val listState = rememberLazyListState()
    ToggleLegend()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(candidates, key = { it.packageName }) { candidate ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = TOGGLE_ROW_END_PADDING),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { detailCandidate = candidate }
                            .padding(vertical = 8.dp)
                            .testTag("$TAG_APP_FILTER_LABEL_PREFIX${candidate.packageName}"),
                    ) {
                        Text(text = candidate.appName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = candidate.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val rule = controller.appRuleFor(candidate.packageName, rules)
                    IconToggle(
                        on = !rule.forward,
                        icon = Icons.Default.NotificationsOff,
                        description = MUTE_TOGGLE_DESCRIPTION,
                        tag = "$TAG_APP_FILTER_CHECKBOX_PREFIX${candidate.packageName}",
                    ) { on ->
                        rules = controller.setMirroredMute(candidate.packageName, candidate.senderDeviceId, on)
                    }
                    IconToggle(
                        on = rule.swipeDismissesSource,
                        icon = Icons.Default.ClearAll,
                        description = SWIPE_DISMISS_TOGGLE_DESCRIPTION,
                        tag = "$TAG_APP_FILTER_SWIPE_PREFIX${candidate.packageName}",
                    ) { on ->
                        rules = controller.setMirroredAppRule(
                            candidate.packageName,
                            candidate.senderDeviceId,
                            rule.copy(swipeDismissesSource = on),
                        )
                    }
                    DetailMenuButton(candidate.packageName) { detailCandidate = candidate }
                }
            }
        }
        lazyScrollbarContent(listState)
    }

    detailCandidate?.let { candidate ->
        DetailDialog(
            label = candidate.appName,
            settings = controller.appRuleFor(candidate.packageName, rules),
            onDismiss = { detailCandidate = null },
            onSave = { settings ->
                rules = controller.setMirroredAppRule(candidate.packageName, candidate.senderDeviceId, settings)
                detailCandidate = null
            },
        )
    }
}
