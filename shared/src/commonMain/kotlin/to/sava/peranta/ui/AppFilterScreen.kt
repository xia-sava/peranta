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

/** 現在のモードとチェックの意味を説明する文言。 */
private fun modeDescription(mode: FilterMode, receiveOnly: Boolean): String {
    val base = when (mode) {
        FilterMode.DENYLIST -> "除外リスト: チェックしたアプリは転送しません"
        FilterMode.ALLOWLIST -> "許可リスト: チェックしたアプリだけ転送します"
    }
    return if (receiveOnly) "$base（この端末が非表示にしたものを表示中）" else base
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
            app = app,
            rule = rules.firstOrNull { it.packageName == app.packageName },
            onDismiss = { detailApp = null },
            onSave = { priorityOverride, redact ->
                rules = controller.updateRules { current ->
                    updatePackageDetail(current, app.packageName, priorityOverride, redact, mode, app.isSystemApp)
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
    app: InstalledApp,
    rule: FilterRule?,
    onDismiss: () -> Unit,
    onSave: (priorityOverride: Priority?, redact: Boolean) -> Unit,
) {
    var priorityOverride by remember(app.packageName) { mutableStateOf(rule?.priorityOverride) }
    var redact by remember(app.packageName) { mutableStateOf(rule?.redact ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = app.label) },
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(priorityOverride, redact) },
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

@Composable
private fun ReceiveRoleContent(
    controller: AppFilterController,
    items: StateFlow<List<TimelineItem>>?,
    lazyScrollbarContent: @Composable BoxScope.(listState: LazyListState) -> Unit,
) {
    val timeline = items?.collectAsState()?.value ?: emptyList()
    val candidates = remember(timeline) { historyPackagesFrom(timeline) }
    var rules by remember { mutableStateOf(controller.load().filterRules) }

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
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(candidates, key = { it.packageName }) { candidate ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(text = candidate.appName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = candidate.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Checkbox(
                        checked = isPackageChecked(rules, candidate.packageName, FilterMode.DENYLIST, isSystemPackage = false),
                        onCheckedChange = { checked ->
                            rules = controller.setMirroredMute(candidate.packageName, candidate.senderDeviceId, checked)
                        },
                        modifier = Modifier.testTag("$TAG_APP_FILTER_CHECKBOX_PREFIX${candidate.packageName}"),
                    )
                }
            }
        }
        lazyScrollbarContent(listState)
    }
}
