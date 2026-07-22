package to.sava.peranta.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

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
 * タイムラインをメインに、サブ画面をアプリバー共有で差し替える画面シェル（§10）。
 *
 * タイムライン表示時はアプリバー左端にドロワー開閉、メインタイトルに「Peranta」と自端末名
 * （[deviceLabel] があれば一体表示）、サブ行に ntfy サーバ名（[serverLabel]、config 由来のみで
 * ネットワーク不要）を出す。サーバ名の隣の [serverTrailing] は将来のロスタードロップダウン用の
 * アンカースロット。サブ画面表示時は左端が戻る（タイムラインへ [onNavigate]）に変わり、タイトルは
 * 画面名になる。ドロワーはサブ画面でもエッジスワイプで開ける（[ModalNavigationDrawer] の標準挙動）。
 *
 * ドロワーには行き先を列挙する。受信のセットアップと動作チェックは設定画面のセットアップ状況の行から開くため、
 * ドロワーには並べない。現在地は [NavigationDrawerItem] の選択でハイライトする。項目タップで
 * [onNavigate] へ一元化して遷移し、ドロワーを閉じる。[drawerExtras] はドロワーヘッダ下の将来スロット。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        modifier = modifier,
        drawerState = drawerState,
        drawerContent = {
            ShellDrawer(
                destination = destination,
                serverLabel = serverLabel,
                deviceLabel = deviceLabel,
                drawerExtras = drawerExtras,
                onSelect = { target ->
                    scope.launch { drawerState.close() }
                    onNavigate(target)
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                ShellTopBar(
                    destination = destination,
                    serverLabel = serverLabel,
                    deviceLabel = deviceLabel,
                    serverTrailing = serverTrailing,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onNavigate = onNavigate,
                )
            },
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                content(destination)
            }
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
    onOpenDrawer: () -> Unit,
    onNavigate: (ShellDestination) -> Unit,
) {
    TopAppBar(
        navigationIcon = {
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
        },
        title = {
            if (destination == ShellDestination.Timeline) {
                TimelineTitle(serverLabel = serverLabel, deviceLabel = deviceLabel, serverTrailing = serverTrailing)
            } else {
                Text(
                    text = destinationTitle(destination),
                    style = MaterialTheme.typography.titleLarge,
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

@Composable
private fun ShellDrawer(
    destination: ShellDestination,
    serverLabel: String?,
    deviceLabel: String?,
    drawerExtras: (@Composable ColumnScope.() -> Unit)?,
    onSelect: (ShellDestination) -> Unit,
) {
    ModalDrawerSheet {
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
            DrawerItem(ShellDestination.Timeline, "タイムライン", destination, onSelect)
            DrawerItem(ShellDestination.AppFilter, "アプリフィルタ", destination, onSelect)
            DrawerItem(ShellDestination.Settings, "設定", destination, onSelect)
            HorizontalDivider()
            DrawerItem(ShellDestination.PairingImport, "QR で設定を取り込む", destination, onSelect)
        }
    }
}

@Composable
private fun DrawerItem(
    target: ShellDestination,
    label: String,
    current: ShellDestination,
    onSelect: (ShellDestination) -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(text = label) },
        selected = target == current,
        onClick = { onSelect(target) },
        modifier = Modifier
            .padding(NavigationDrawerItemDefaults.ItemPadding)
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
    ShellDestination.PairingImport -> "QR で設定を取り込む"
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
