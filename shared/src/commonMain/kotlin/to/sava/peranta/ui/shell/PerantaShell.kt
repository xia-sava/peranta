package to.sava.peranta.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import to.sava.peranta.ui.platformUiDensitySpec
import to.sava.peranta.ui.setup.ReceiveSetupSteps

/** これ以上のコンテンツ幅では常設ドロワーの広幅レイアウトに切り替える閾値。 */
internal val WIDE_LAYOUT_MIN_WIDTH: Dp = 840.dp

/** ドロワーシートの幅（M3 既定の 360dp より狭める）。開閉式・常設の両シートに適用する。 */
internal val DRAWER_SHEET_WIDTH: Dp = 280.dp

/**
 * 画面シェルが束ねる行き先（§10）。タイムラインをメインに、サブ画面をアプリバー共有で差し替える。
 * ウィザード・共有・未ペアリング着地はシェルの外にあるモーダルなタスクで、この列挙には含めない。
 */
enum class ShellDestination { Timeline, AppFilter, Settings, ReceiveSetup, HealthCheck, PairingImport }

/**
 * シェルの遷移決定。[destination] は確定した遷移先、[reflectSettings] は設定を離れるため
 * 設定反映（受信パイプラインの再構築）を通す必要があるかを表す（§10.2）。
 */
data class ShellNavigation(val destination: ShellDestination, val reflectSettings: Boolean)

/**
 * [from] から [to] への遷移を決める。設定の変更が起こりうる画面（設定・取り込み）を離れるときは、
 * 遷移先に依らず設定反映を通す。反映（再生成）後も遷移先を保つため、呼び出し側は
 * [ShellNavigation.destination] を先に確定してから反映を実行する（§10.2）。
 */
fun shellNavigate(from: ShellDestination, to: ShellDestination): ShellNavigation =
    ShellNavigation(
        destination = to,
        reflectSettings = from != to &&
            (from == ShellDestination.Settings || from == ShellDestination.PairingImport),
    )

/**
 * 起動時の動作チェックで未達だった項目 id の集合から、警告バナーのタップで開く画面を導く（§10.5）。
 * [unmetHealthCheckIds] が空なら誘導先は無く、バナーを出さない（null）。未達がすべて受信経路系
 * （[ReceiveSetupSteps.orderedIds] の手順）なら、その作業台の受信のセットアップへ導く。受信経路系以外を
 * 1 つでも含むなら、権限・常駐も点検できる動作チェックへ導く。
 */
fun setupBannerTarget(unmetHealthCheckIds: Set<String>): ShellDestination? {
    if (unmetHealthCheckIds.isEmpty()) return null
    val receivePathIds = ReceiveSetupSteps.orderedIds.toSet()
    return if (unmetHealthCheckIds.all { it in receivePathIds }) {
        ShellDestination.ReceiveSetup
    } else {
        ShellDestination.HealthCheck
    }
}

/**
 * タイムラインをメインに、サブ画面をアプリバー共有で差し替える画面シェル（§10）。
 *
 * コンテンツ幅で出し方を切り替える。狭幅（[WIDE_LAYOUT_MIN_WIDTH] 未満）は [ModalNavigationDrawer] で、
 * タイムライン表示時はアプリバー左端にドロワー開閉、サブ画面表示時は左端が戻る（タイムラインへ
 * [onNavigate]）になり、ドロワーはエッジスワイプで開ける（標準挙動）。広幅（[WIDE_LAYOUT_MIN_WIDTH]
 * 以上）は [PermanentNavigationDrawer] で左に常設ドロワーを置き、アプリバー左端のアイコンは出さない
 * （現在地は常設一覧のハイライトが示し、タイムラインへはドロワーの「タイムライン」で戻る）。
 *
 * どちらの幅でもアプリバーのタイトルは共通で、メインタイトルに「Peranta」と自端末名（[deviceLabel]
 * があれば一体表示）、サブ行に ntfy サーバ名（[serverLabel]、config 由来のみでネットワーク不要）を出す。
 * サーバ名の隣の [serverTrailing] は将来のロスタードロップダウン用のアンカースロット。サブ画面表示時は
 * タイトルが画面名になる。
 *
 * ドロワーの中身は両レイアウトで共通で、行き先を列挙する。受信のセットアップ・動作チェック・
 * 接続設定と暗号キーの取り込みは設定画面のセットアップ状況の行から開くため、ドロワーには並べない。
 * 現在地は [NavigationDrawerItem] の選択でハイライトする。項目タップで [onNavigate] へ一元化して遷移し、
 * 狭幅ではあわせてドロワーを閉じる。[drawerExtras] はドロワーヘッダ下の将来スロット。
 */
@Composable
fun PerantaShell(
    destination: ShellDestination,
    onNavigate: (ShellDestination) -> Unit,
    serverLabel: String?,
    deviceLabel: String?,
    modifier: Modifier = Modifier,
    serverTrailing: (@Composable () -> Unit)? = null,
    drawerExtras: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable (ShellDestination) -> Unit,
) {
    // レイアウト切替（リサイズで閾値をまたぐ）が起きても content 配下のコンポジションと状態を
    // 破棄しないよう、content は movableContentOf で両レイアウト共有の単一サブツリーにする。
    val currentContent by rememberUpdatedState(content)
    val movableContent = remember {
        movableContentOf { shellDestination: ShellDestination -> currentContent(shellDestination) }
    }
    BoxWithConstraints(modifier = modifier) {
        if (maxWidth >= WIDE_LAYOUT_MIN_WIDTH) {
            WideShell(
                destination = destination,
                onNavigate = onNavigate,
                serverLabel = serverLabel,
                deviceLabel = deviceLabel,
                serverTrailing = serverTrailing,
                drawerExtras = drawerExtras,
                content = movableContent,
            )
        } else {
            NarrowShell(
                destination = destination,
                onNavigate = onNavigate,
                serverLabel = serverLabel,
                deviceLabel = deviceLabel,
                serverTrailing = serverTrailing,
                drawerExtras = drawerExtras,
                content = movableContent,
            )
        }
    }
}

/** 狭幅レイアウト。開閉式ドロワーと、アプリバー左端の開閉／戻るアイコンを持つ。 */
@Composable
private fun NarrowShell(
    destination: ShellDestination,
    onNavigate: (ShellDestination) -> Unit,
    serverLabel: String?,
    deviceLabel: String?,
    serverTrailing: (@Composable () -> Unit)?,
    drawerExtras: (@Composable ColumnScope.() -> Unit)?,
    content: @Composable (ShellDestination) -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(DRAWER_SHEET_WIDTH).testTag(TAG_SHELL_DRAWER_SHEET)) {
                ShellDrawerContent(
                    destination = destination,
                    serverLabel = serverLabel,
                    deviceLabel = deviceLabel,
                    drawerExtras = drawerExtras,
                    onSelect = { target ->
                        scope.launch { drawerState.close() }
                        onNavigate(target)
                    },
                )
            }
        },
    ) {
        ShellContent(
            destination = destination,
            serverLabel = serverLabel,
            deviceLabel = deviceLabel,
            serverTrailing = serverTrailing,
            showNavigationIcon = true,
            onOpenDrawer = { scope.launch { drawerState.open() } },
            onNavigate = onNavigate,
            content = content,
        )
    }
}

/** 広幅レイアウト。左に常設ドロワーを置き、アプリバー左端のアイコンは出さない。 */
@Composable
private fun WideShell(
    destination: ShellDestination,
    onNavigate: (ShellDestination) -> Unit,
    serverLabel: String?,
    deviceLabel: String?,
    serverTrailing: (@Composable () -> Unit)?,
    drawerExtras: (@Composable ColumnScope.() -> Unit)?,
    content: @Composable (ShellDestination) -> Unit,
) {
    PermanentNavigationDrawer(
        drawerContent = {
            PermanentDrawerSheet(modifier = Modifier.width(DRAWER_SHEET_WIDTH).testTag(TAG_SHELL_DRAWER_SHEET)) {
                ShellDrawerContent(
                    destination = destination,
                    serverLabel = serverLabel,
                    deviceLabel = deviceLabel,
                    drawerExtras = drawerExtras,
                    onSelect = onNavigate,
                )
            }
        },
    ) {
        ShellContent(
            destination = destination,
            serverLabel = serverLabel,
            deviceLabel = deviceLabel,
            serverTrailing = serverTrailing,
            showNavigationIcon = false,
            onOpenDrawer = {},
            onNavigate = onNavigate,
            content = content,
        )
    }
}

/** ドロワー右側の本体。アプリバーとコンテンツを両レイアウトで共通に組む。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShellContent(
    destination: ShellDestination,
    serverLabel: String?,
    deviceLabel: String?,
    serverTrailing: (@Composable () -> Unit)?,
    showNavigationIcon: Boolean,
    onOpenDrawer: () -> Unit,
    onNavigate: (ShellDestination) -> Unit,
    content: @Composable (ShellDestination) -> Unit,
) {
    Scaffold(
        topBar = {
            ShellTopBar(
                destination = destination,
                serverLabel = serverLabel,
                deviceLabel = deviceLabel,
                serverTrailing = serverTrailing,
                showNavigationIcon = showNavigationIcon,
                onOpenDrawer = onOpenDrawer,
                onNavigate = onNavigate,
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            content(destination)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShellTopBar(
    destination: ShellDestination,
    serverLabel: String?,
    deviceLabel: String?,
    serverTrailing: (@Composable () -> Unit)?,
    showNavigationIcon: Boolean,
    onOpenDrawer: () -> Unit,
    onNavigate: (ShellDestination) -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            if (showNavigationIcon) {
                if (destination == ShellDestination.Timeline) {
                    IconButton(onClick = onOpenDrawer, modifier = Modifier.testTag(TAG_SHELL_MENU)) {
                        Text(text = "☰", style = MaterialTheme.typography.titleLarge)
                    }
                } else {
                    IconButton(
                        onClick = { onNavigate(ShellDestination.Timeline) },
                        modifier = Modifier.testTag(TAG_SHELL_BACK),
                    ) {
                        Text(text = "←", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        },
        title = {
            if (destination == ShellDestination.Timeline) {
                TimelineTitle(serverLabel = serverLabel, deviceLabel = deviceLabel, serverTrailing = serverTrailing)
            } else {
                Text(
                    text = destinationTitle(destination),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(TAG_SHELL_TITLE),
                )
            }
        },
    )
}

@Composable
private fun TimelineTitle(
    serverLabel: String?,
    deviceLabel: String?,
    serverTrailing: (@Composable () -> Unit)?,
) {
    Column {
        Text(
            text = deviceLabel?.let { "Peranta ・ $it" } ?: "Peranta",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag(TAG_SHELL_TITLE),
        )
        if (serverLabel != null || serverTrailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (serverLabel != null) {
                    Text(
                        text = serverLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(TAG_SHELL_SERVER),
                    )
                }
                serverTrailing?.invoke()
            }
        }
    }
}

/** ドロワーの中身（ヘッダ・将来スロット・行き先項目）。開閉式と常設のどちらのシートにも入れる。 */
@Composable
private fun ShellDrawerContent(
    destination: ShellDestination,
    serverLabel: String?,
    deviceLabel: String?,
    drawerExtras: (@Composable ColumnScope.() -> Unit)?,
    onSelect: (ShellDestination) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp)) {
            Text(text = "Peranta", style = MaterialTheme.typography.titleLarge)
            drawerSubtitle(deviceLabel, serverLabel)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        drawerExtras?.invoke(this)
        HorizontalDivider()
        val itemHeight = platformUiDensitySpec().drawerItemHeight
        DrawerItem(ShellDestination.Timeline, "タイムライン", destination, itemHeight, onSelect)
        DrawerItem(ShellDestination.AppFilter, "アプリフィルタ", destination, itemHeight, onSelect)
        DrawerItem(ShellDestination.Settings, "設定", destination, itemHeight, onSelect)
    }
}

@Composable
private fun DrawerItem(
    target: ShellDestination,
    label: String,
    current: ShellDestination,
    itemHeight: Dp,
    onSelect: (ShellDestination) -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(text = label) },
        selected = target == current,
        onClick = { onSelect(target) },
        modifier = Modifier
            .padding(NavigationDrawerItemDefaults.ItemPadding)
            .height(itemHeight)
            .testTag("$TAG_SHELL_DRAWER_ITEM_PREFIX${target.name}"),
    )
}

/** ドロワーヘッダの副題（端末名＠サーバ名）。どちらも無いときは出さない。 */
private fun drawerSubtitle(deviceLabel: String?, serverLabel: String?): String? = when {
    deviceLabel != null && serverLabel != null -> "$deviceLabel @ $serverLabel"
    deviceLabel != null -> deviceLabel
    serverLabel != null -> serverLabel
    else -> null
}

/** サブ画面のアプリバータイトル。タイムラインは 2 行タイトルを別に組むため対象外。 */
private fun destinationTitle(destination: ShellDestination): String = when (destination) {
    ShellDestination.Timeline -> "Peranta"
    ShellDestination.AppFilter -> "アプリフィルタ"
    ShellDestination.Settings -> "設定"
    ShellDestination.ReceiveSetup -> "受信のセットアップ"
    ShellDestination.HealthCheck -> "動作チェック"
    ShellDestination.PairingImport -> "接続設定と暗号キーを取り込む"
}

/** ドロワー開閉（ハンバーガー）のタグ。 */
const val TAG_SHELL_MENU: String = "shell-menu"

/** サブ画面の戻る（タイムラインへ）のタグ。 */
const val TAG_SHELL_BACK: String = "shell-back"

/** アプリバーのメインタイトルのタグ。 */
const val TAG_SHELL_TITLE: String = "shell-title"

/** アプリバーのサーバ名（サブ行）のタグ。 */
const val TAG_SHELL_SERVER: String = "shell-server"

/** ドロワー項目のタグ接頭辞（末尾に [ShellDestination] 名を付ける）。 */
const val TAG_SHELL_DRAWER_ITEM_PREFIX: String = "shell-drawer-"

/** ドロワーシート（開閉式・常設とも）のタグ。 */
const val TAG_SHELL_DRAWER_SHEET: String = "shell-drawer-sheet"
