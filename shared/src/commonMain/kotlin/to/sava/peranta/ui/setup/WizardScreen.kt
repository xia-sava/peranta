package to.sava.peranta.ui.setup

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.pairing.PairingImportController
import to.sava.peranta.platform.PlatformCapabilities
import to.sava.peranta.pairing.SettingsController
import to.sava.peranta.ui.HealthCheckItem
import to.sava.peranta.ui.HealthCheckState
import to.sava.peranta.ui.HealthChecker
import to.sava.peranta.ui.PairingScanContent
import to.sava.peranta.ui.TAG_DEVICE_NAME
import to.sava.peranta.ui.TAG_HOST
import to.sava.peranta.ui.TAG_PORT
import to.sava.peranta.ui.TAG_TOKEN

/** QR の自動非表示までの既定時間。 */
private const val DEFAULT_QR_VISIBLE_MILLIS: Long = 60_000L

/** 操作直後に反映を追いかける自動再チェックの回数と間隔（[ReceiveSetupScreen] と同じ型）。 */
private const val RECHECK_COUNT: Int = 3
private const val RECHECK_INTERVAL_MILLIS: Long = 2_000L

/** 鍵を作り直すと全端末で QR の読み直しが必要になる旨の警告文。 */
private const val ROTATE_WARNING_BODY: String =
    "前の鍵は破棄され、全端末で QR の読み直しが必要になります。続けますか？"

/**
 * 設定・診断のセットアップをページ列で案内するウィザード。
 * ページ列は [WizardFlow.pages] が [caps] と回答済み [WizardAnswers] / [PerantaConfig][to.sava.peranta.config.PerantaConfig]
 * から算出し、編集ページは [SettingsController] 経由で、項目ページは [provider] の [SetupItemUi] を
 * [SetupChecklist] のページ内モードで描く。入力・操作は全て即時保存し、いつでも「閉じる」で離脱できる。
 * 再入時は最初の未完了ページから始める。
 *
 * プラットフォーム依存は各スロットで注入する。[qrContent] は端末追加ページの QR 描画、
 * [importController] と [onRequestScan] は QR 取り込みページ、[onCopyText] / [onCopyPairingUri] はコピー、
 * [scrollbarContent] はページ本体のスクロールバー、[healthChecker] は完了ページの診断集計に使う。
 * [onClose] は「閉じる」「タイムラインへ」で呼ぶ。
 */
@Composable
fun WizardScreen(
    caps: PlatformCapabilities,
    controller: SettingsController,
    provider: SetupItemsProvider,
    healthChecker: HealthChecker,
    modifier: Modifier = Modifier,
    importController: PairingImportController? = null,
    qrContent: @Composable (uri: String) -> Unit = {},
    onCopyPairingUri: ((String) -> Unit)? = null,
    onCopyText: ((text: String, sensitive: Boolean) -> Unit)? = null,
    onRequestScan: ((onResult: (String?) -> Unit) -> Unit)? = null,
    scrollbarContent: @Composable BoxScope.(scrollState: ScrollState) -> Unit = {},
    qrVisibleMillis: Long = DEFAULT_QR_VISIBLE_MILLIS,
    externalRefreshKey: Int = 0,
    onClose: (() -> Unit)? = null,
    onSaved: (() -> Unit)? = null,
) {
    var answers by remember { mutableStateOf(WizardAnswers()) }
    var config by remember { mutableStateOf(controller.load()) }
    var host by remember { mutableStateOf(config.host) }
    var accessToken by remember { mutableStateOf(config.accessToken.orEmpty()) }
    var deviceName by remember { mutableStateOf(config.deviceName.orEmpty()) }
    var port by remember { mutableStateOf(config.port?.toString().orEmpty()) }

    var items by remember { mutableStateOf<List<SetupItemUi>?>(null) }
    var manualRefresh by remember { mutableStateOf(0) }
    var followUpRechecks by remember { mutableStateOf(0) }

    var currentIndex by remember { mutableStateOf(0) }
    var didInit by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }

    var pairingUri by remember { mutableStateOf<String?>(null) }
    var showRotateWarning by remember { mutableStateOf(false) }
    var skipConfirm by remember { mutableStateOf<WizardPage?>(null) }
    var doneItems by remember { mutableStateOf<List<HealthCheckItem>?>(null) }

    val pages = WizardFlow.pages(caps, config, answers)
    val index = currentIndex.coerceIn(0, pages.lastIndex)
    val page = pages[index]
    val completed = WizardFlow.isPageComplete(page, config, answers, items ?: emptyList())

    fun reload() {
        val fresh = controller.load()
        config = fresh
        host = fresh.host
        accessToken = fresh.accessToken.orEmpty()
        deviceName = fresh.deviceName.orEmpty()
        port = fresh.port?.toString().orEmpty()
    }

    fun persistConnection() {
        controller.saveConnectionSettings(
            host = host,
            accessToken = accessToken,
            deviceName = deviceName,
            port = port.toIntOrNull(),
            persistSensitiveHistory = config.persistSensitiveHistory,
            attachFullTextWhenTruncated = config.attachFullTextWhenTruncated,
        )
        config = controller.load()
        dirty = true
    }

    fun goNext() {
        if (dirty) {
            onSaved?.invoke()
            dirty = false
        }
        if (index < pages.lastIndex) currentIndex = index + 1
    }

    fun rotateKey() {
        controller.rotateSharedKey()
        pairingUri = null
        reload()
        onSaved?.invoke()
    }

    fun showPairingQr() {
        val uri = controller.buildPairingUri()
        pairingUri = uri
        if (uri != null) {
            reload()
            onSaved?.invoke()
        }
    }

    fun chooseForward(forward: Boolean) {
        answers = answers.copy(forward = forward)
        controller.saveSendRoleSettings(forward, config.smsDirectReceive)
        reload()
        onSaved?.invoke()
    }

    fun setSmsDirectReceive(checked: Boolean) {
        controller.saveSendRoleSettings(config.sendEnabled, checked)
        reload()
        onSaved?.invoke()
    }

    fun onActionInvoked() {
        manualRefresh++
        followUpRechecks = RECHECK_COUNT
    }

    LaunchedEffect(externalRefreshKey, manualRefresh) {
        items = provider.items()
    }

    LaunchedEffect(followUpRechecks) {
        if (followUpRechecks <= 0) return@LaunchedEffect
        delay(RECHECK_INTERVAL_MILLIS)
        manualRefresh++
        followUpRechecks--
    }

    // 初回に項目が揃ったら、最初の未完了ページへ着地する（再入時の続きから開始）。
    LaunchedEffect(items) {
        val loaded = items
        if (!didInit && loaded != null) {
            currentIndex = WizardFlow.firstIncompletePage(pages, config, answers, loaded)
                ?.let { pages.indexOf(it).coerceAtLeast(0) }
                ?: pages.lastIndex
            didInit = true
        }
    }

    // QR は時間制限つきで自動的に隠す。
    LaunchedEffect(pairingUri) {
        if (pairingUri == null) return@LaunchedEffect
        delay(qrVisibleMillis)
        pairingUri = null
    }

    // 完了ページでは診断を集計して残項目を求める。ON_RESUME（externalRefreshKey）でも取り直す。
    LaunchedEffect(page.id, manualRefresh, externalRefreshKey) {
        doneItems = if (page.id == WizardFlow.PAGE_DONE) healthChecker.check() else null
    }

    // 途中離脱時も編集内容を確実に反映する（接続編集→閉じるで保存契機を取りこぼさない）。
    DisposableEffect(Unit) {
        onDispose { if (dirty) onSaved?.invoke() }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    WizardHeader(page = page, completed = completed, onClose = onClose)

                    WizardPageBody(
                        page = page,
                        config = config,
                        answers = answers,
                        items = items,
                        host = host,
                        accessToken = accessToken,
                        deviceName = deviceName,
                        port = port,
                        pairingUri = pairingUri,
                        doneItems = doneItems,
                        qrContent = qrContent,
                        onCopyPairingUri = onCopyPairingUri,
                        onCopyText = onCopyText,
                        onRequestScan = onRequestScan,
                        importController = importController,
                        onHostChange = { host = it; persistConnection() },
                        onTokenChange = { accessToken = it; persistConnection() },
                        onDeviceNameChange = { deviceName = it; persistConnection() },
                        onPortChange = { port = it; persistConnection() },
                        onRotate = { if (config.hasSharedKey) showRotateWarning = true else rotateKey() },
                        onShowPairingQr = ::showPairingQr,
                        onHidePairingQr = { pairingUri = null },
                        onImported = {
                            reload()
                            onSaved?.invoke()
                            if (index < pages.lastIndex) currentIndex = index + 1
                        },
                        onChooseSource = { answers = answers.copy(source = it) },
                        onChooseForward = { chooseForward(it) },
                        onSmsDirectReceiveChange = { setSmsDirectReceive(it) },
                        onActionInvoked = ::onActionInvoked,
                        onOpenTimeline = onClose,
                    )
                }
                scrollbarContent(scrollState)
            }

            WizardFooter(
                page = page,
                canGoBack = index > 0,
                nextEnabled = completed,
                onBack = { currentIndex = index - 1 },
                onNext = ::goNext,
                onSkip = { skipConfirm = page },
            )
        }
    }

    if (showRotateWarning) {
        AlertDialog(
            onDismissRequest = { showRotateWarning = false },
            title = { Text(text = "鍵を作り直しますか？") },
            text = { Text(text = ROTATE_WARNING_BODY) },
            confirmButton = {
                TextButton(
                    onClick = { showRotateWarning = false; rotateKey() },
                    modifier = Modifier.testTag(TAG_WIZARD_ROTATE_CONFIRM),
                ) {
                    Text(text = "破棄して作成")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRotateWarning = false }) { Text(text = "やめる") }
            },
        )
    }

    skipConfirm?.let { target ->
        AlertDialog(
            onDismissRequest = { skipConfirm = null },
            title = { Text(text = "あとで設定しますか？") },
            text = { Text(text = skipConsequence(target.id)) },
            confirmButton = {
                TextButton(
                    onClick = { skipConfirm = null; goNext() },
                    modifier = Modifier.testTag(TAG_WIZARD_SKIP_CONFIRM),
                ) {
                    Text(text = "あとで設定する")
                }
            },
            dismissButton = {
                TextButton(onClick = { skipConfirm = null }) { Text(text = "戻る") }
            },
        )
    }
}

@Composable
private fun WizardHeader(page: WizardPage, completed: Boolean, onClose: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = page.title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag(TAG_WIZARD_TITLE),
            )
            if (completed && page.id != WizardFlow.PAGE_DONE) {
                Text(
                    text = "設定済み",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag(TAG_WIZARD_DONE_BADGE),
                )
            }
        }
        if (onClose != null) {
            TextButton(onClick = onClose, modifier = Modifier.testTag(TAG_WIZARD_CLOSE)) {
                Text(text = "閉じる")
            }
        }
    }
}

@Composable
private fun WizardPageBody(
    page: WizardPage,
    config: PerantaConfig,
    answers: WizardAnswers,
    items: List<SetupItemUi>?,
    host: String,
    accessToken: String,
    deviceName: String,
    port: String,
    pairingUri: String?,
    doneItems: List<HealthCheckItem>?,
    qrContent: @Composable (uri: String) -> Unit,
    onCopyPairingUri: ((String) -> Unit)?,
    onCopyText: ((text: String, sensitive: Boolean) -> Unit)?,
    onRequestScan: ((onResult: (String?) -> Unit) -> Unit)?,
    importController: PairingImportController?,
    onHostChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onDeviceNameChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onRotate: () -> Unit,
    onShowPairingQr: () -> Unit,
    onHidePairingQr: () -> Unit,
    onImported: () -> Unit,
    onChooseSource: (WizardSourceChoice) -> Unit,
    onChooseForward: (Boolean) -> Unit,
    onSmsDirectReceiveChange: (Boolean) -> Unit,
    onActionInvoked: () -> Unit,
    onOpenTimeline: (() -> Unit)?,
) {
    when {
        page.id == WizardFlow.PAGE_SOURCE -> SourceChoiceBody(answers, onChooseSource)
        page.id == WizardFlow.PAGE_ROLE -> RoleChoiceBody(config, answers, onChooseForward, onSmsDirectReceiveChange)
        page.id == WizardFlow.PAGE_DONE -> DoneBody(doneItems, onOpenTimeline)
        page.id == WizardFlow.PAGE_QR_IMPORT ->
            QrImportBody(importController, onRequestScan, onImported)

        page.itemIds.isNotEmpty() -> ItemPageBody(page, items, onCopyText, onActionInvoked)
        page.id == WizardFlow.PAGE_CONNECTION -> ConnectionBody(host, accessToken, port, onHostChange, onTokenChange, onPortChange)
        page.id == WizardFlow.PAGE_DEVICE_NAME -> DeviceNameBody(deviceName, onDeviceNameChange)
        page.id == WizardFlow.PAGE_KEY -> KeyBody(config, onRotate)
        page.id == WizardFlow.PAGE_PAIRING ->
            PairingBody(pairingUri, qrContent, onCopyPairingUri, onShowPairingQr, onHidePairingQr)
    }
}

@Composable
private fun ConnectionBody(
    host: String,
    accessToken: String,
    port: String,
    onHostChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
) {
    Text(text = "接続先のサーバとアクセストークンを入力します。", style = MaterialTheme.typography.bodyMedium)
    HostField(value = host, onValueChange = onHostChange, tag = wizardTag(TAG_HOST))
    TokenField(value = accessToken, onValueChange = onTokenChange, tag = wizardTag(TAG_TOKEN))
    PortField(value = port, onValueChange = onPortChange, tag = wizardTag(TAG_PORT))
}

@Composable
private fun DeviceNameBody(deviceName: String, onDeviceNameChange: (String) -> Unit) {
    Text(text = "この端末の表示名を入力します。", style = MaterialTheme.typography.bodyMedium)
    DeviceNameField(value = deviceName, onValueChange = onDeviceNameChange, tag = wizardTag(TAG_DEVICE_NAME))
}

@Composable
private fun KeyBody(config: PerantaConfig, onRotate: () -> Unit) {
    Text(
        text = "全端末で共有する暗号鍵を作成します。作成した鍵は次のページで QR として配布します。",
        style = MaterialTheme.typography.bodyMedium,
    )
    KeyStatusText(hasKey = config.hasSharedKey, keyId = config.keyId)
    OutlinedButton(onClick = onRotate, modifier = Modifier.testTag(TAG_WIZARD_ROTATE)) {
        Text(text = "鍵を作る")
    }
}

@Composable
private fun PairingBody(
    pairingUri: String?,
    qrContent: @Composable (uri: String) -> Unit,
    onCopyPairingUri: ((String) -> Unit)?,
    onShowPairingQr: () -> Unit,
    onHidePairingQr: () -> Unit,
) {
    Text(text = "QR を表示し、他の端末のカメラで読み取ってペアリングします。", style = MaterialTheme.typography.bodyMedium)
    OutlinedButton(onClick = onShowPairingQr, modifier = Modifier.testTag(TAG_WIZARD_ADD_DEVICE)) {
        Text(text = "QR を表示する")
    }
    pairingUri?.let { uri ->
        PairingQrSection(
            uri = uri,
            qrContent = qrContent,
            onCopyPairingUri = onCopyPairingUri,
            onCopied = {},
            onHide = onHidePairingQr,
        )
    }
}

@Composable
private fun QrImportBody(
    importController: PairingImportController?,
    onRequestScan: ((onResult: (String?) -> Unit) -> Unit)?,
    onApplied: () -> Unit,
) {
    if (importController == null) {
        Text(text = "この端末では QR の取り込みに対応していません。", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text(
        text = "設定元の端末が表示した QR を読み取ると、サーバ・トークン・共有鍵をまとめて取り込みます。",
        style = MaterialTheme.typography.bodyMedium,
    )
    // 外側のウィザードが縦スクロールを持つため、スクロールコンテナを持たない中身だけを埋め込む。
    PairingScanContent(
        controller = importController,
        onRequestScan = onRequestScan,
        showHeader = false,
        showDescription = false,
        onApplied = onApplied,
    )
}

@Composable
private fun ItemPageBody(
    page: WizardPage,
    items: List<SetupItemUi>?,
    onCopyText: ((text: String, sensitive: Boolean) -> Unit)?,
    onActionInvoked: () -> Unit,
) {
    val loaded = items
    if (loaded == null) {
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.testTag(TAG_WIZARD_LOADING))
        }
        return
    }
    val pageItems = withRecheckOnAction(loaded.filter { it.id in page.itemIds }, onActionInvoked)
    SetupChecklist(items = pageItems, mode = SetupChecklistMode.IN_PAGE, onCopyText = onCopyText)
    if (page.id == ReceiveSetupSteps.UNIFIED_PUSH_ID) {
        Text(
            text = "登録しても設定サーバと一致しないときは、「戻る」で前の手順に戻り、ntfy のサーバ設定を直してください。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // SMS は遷移先のアプリ情報画面だけでは操作手順が分からないため、この項目に限り説明文を表示する。
    if (page.id == WizardFlow.PAGE_PERM_SMS) {
        pageItems.firstOrNull { it.id == WizardFlow.ITEM_SMS }?.description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SourceChoiceBody(answers: WizardAnswers, onChooseSource: (WizardSourceChoice) -> Unit) {
    Text(text = "設定をどう受け取るか選んでください。", style = MaterialTheme.typography.bodyMedium)
    ChoiceCard(
        label = "他の端末の QR を読んで参加する",
        selected = answers.source == WizardSourceChoice.JOIN,
        tag = TAG_WIZARD_SOURCE_JOIN,
        onClick = { onChooseSource(WizardSourceChoice.JOIN) },
    )
    ChoiceCard(
        label = "この端末を設定元にする",
        selected = answers.source == WizardSourceChoice.BE_SOURCE,
        tag = TAG_WIZARD_SOURCE_BE,
        onClick = { onChooseSource(WizardSourceChoice.BE_SOURCE) },
    )
}

@Composable
private fun RoleChoiceBody(
    config: PerantaConfig,
    answers: WizardAnswers,
    onChooseForward: (Boolean) -> Unit,
    onSmsDirectReceiveChange: (Boolean) -> Unit,
) {
    Text(
        text = "この端末に届く通知や SMS を、他の端末へ自動転送しますか？",
        style = MaterialTheme.typography.bodyMedium,
    )
    ChoiceCard(
        label = "自動転送する（この端末に届く通知や SMS を他の端末へ送る）",
        selected = answers.forward == true,
        tag = TAG_WIZARD_ROLE_SEND,
        onClick = { onChooseForward(true) },
    )
    ChoiceCard(
        label = "転送しない（この端末に届く通知や SMS を他の端末へ送らない）",
        selected = answers.forward == false,
        tag = TAG_WIZARD_ROLE_RECEIVE,
        onClick = { onChooseForward(false) },
    )
    if (answers.forward == true) {
        LabeledCheckbox(
            checked = config.smsDirectReceive,
            onCheckedChange = onSmsDirectReceiveChange,
            label = "SMS を直接受信して転送する",
            tag = TAG_WIZARD_SMS_DIRECT_RECEIVE,
        )
        Text(
            text = SMS_DIRECT_RECEIVE_DESCRIPTION,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DoneBody(doneItems: List<HealthCheckItem>?, onOpenTimeline: (() -> Unit)?) {
    if (doneItems == null) {
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.testTag(TAG_WIZARD_LOADING))
        }
        return
    }
    val remaining = doneItems.filter { it.state == HealthCheckState.FAILING }
    if (remaining.isEmpty()) {
        Text(
            text = "すべて設定できました。",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(TAG_WIZARD_ALL_CLEAR),
        )
    } else {
        Text(text = "まだ設定できていないことがあります。", style = MaterialTheme.typography.bodyMedium)
        remaining.forEach { item ->
            Text(
                text = "・${itemConsequence(item.id)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("$TAG_WIZARD_REMAINING_PREFIX${item.id}"),
            )
        }
    }
    if (onOpenTimeline != null) {
        Button(onClick = onOpenTimeline, modifier = Modifier.testTag(TAG_WIZARD_TIMELINE)) {
            Text(text = "タイムラインへ")
        }
    }
}

@Composable
private fun ChoiceCard(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Text(text = if (selected) "✓ $label" else label, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun WizardFooter(
    page: WizardPage,
    canGoBack: Boolean,
    nextEnabled: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canGoBack) {
            OutlinedButton(onClick = onBack, modifier = Modifier.testTag(TAG_WIZARD_BACK)) {
                Text(text = "戻る")
            }
        }
        if (page.id != WizardFlow.PAGE_DONE) {
            if (page.skippable) {
                TextButton(onClick = onSkip, modifier = Modifier.testTag(TAG_WIZARD_SKIP)) {
                    Text(text = "あとで設定する")
                }
            }
            Button(onClick = onNext, enabled = nextEnabled, modifier = Modifier.testTag(TAG_WIZARD_NEXT)) {
                Text(text = "次へ")
            }
        }
    }
}

/** 各項目が未達のとき、機能として何ができなくなるかを説明する（内部状態ベースの説明はしない）。 */
private fun itemConsequence(id: String): String =
    when (id) {
        WizardFlow.ITEM_NLS -> "この端末の通知を他の端末へ送れません。"
        WizardFlow.ITEM_SELF_BATTERY -> "バックグラウンドで通知を取りこぼすことがあります。"
        WizardFlow.ITEM_SMS -> "SMS を受信して転送できません。"
        WizardFlow.ITEM_POST_NOTIFICATIONS -> "受け取った通知をこの端末で表示できません。"
        ReceiveSetupSteps.NTFY_INSTALLED_ID, ReceiveSetupSteps.SERVER_CONFIG_ID, ReceiveSetupSteps.UNIFIED_PUSH_ID ->
            "他の端末からの通知や操作を受け取れません。"

        ReceiveSetupSteps.NTFY_BATTERY_ID -> "省電力設定によっては通知の受信が遅れたり止まったりすることがあります。"
        ReceiveSetupSteps.SELF_TEST_ID -> "本当に受信できるかを確認できていません。"
        WizardFlow.ITEM_AUTOSTART -> "サインイン後すぐには受信を始められないことがあります。"
        else -> "一部の機能が使えないことがあります。"
    }

/** 飛ばしたときに何ができなくなるかを機能で説明するスキップ確認文。 */
private fun skipConsequence(pageId: String): String =
    when (pageId) {
        WizardFlow.PAGE_AUTOSTART -> "この設定を飛ばすと、サインイン後すぐには受信を始められないことがあります。"
        WizardFlow.PAGE_REVERSE_CHANNEL ->
            "この設定を飛ばすと、他の端末からこのスマホの通知を消したり、既読にしたりできなくなります。"

        ReceiveSetupSteps.NTFY_BATTERY_ID ->
            "この設定を飛ばすと、省電力設定によっては通知の受信が遅れたり止まったりすることがあります。"

        ReceiveSetupSteps.SELF_TEST_ID -> "この設定を飛ばすと、本当に受信できるかを確認しないまま進みます。"
        else -> "この設定はあとで設定できます。"
    }

/** [SettingsFields] の共有フィールドタグ（[TAG_HOST] 等）に "wizard-" を冠して衝突を避ける。 */
private fun wizardTag(base: String): String = "wizard-$base"

const val TAG_WIZARD_TITLE: String = "wizard-title"
const val TAG_WIZARD_CLOSE: String = "wizard-close"
const val TAG_WIZARD_NEXT: String = "wizard-next"
const val TAG_WIZARD_BACK: String = "wizard-back"
const val TAG_WIZARD_SKIP: String = "wizard-skip"
const val TAG_WIZARD_SKIP_CONFIRM: String = "wizard-skip-confirm"
const val TAG_WIZARD_DONE_BADGE: String = "wizard-done-badge"
const val TAG_WIZARD_LOADING: String = "wizard-loading"
const val TAG_WIZARD_ROTATE: String = "wizard-rotate"
const val TAG_WIZARD_ROTATE_CONFIRM: String = "wizard-rotate-confirm"
const val TAG_WIZARD_ADD_DEVICE: String = "wizard-add-device"
const val TAG_WIZARD_SOURCE_JOIN: String = "wizard-source-join"
const val TAG_WIZARD_SOURCE_BE: String = "wizard-source-be"
const val TAG_WIZARD_ROLE_SEND: String = "wizard-role-send"
const val TAG_WIZARD_ROLE_RECEIVE: String = "wizard-role-receive"
const val TAG_WIZARD_SMS_DIRECT_RECEIVE: String = "wizard-sms-direct-receive"
const val TAG_WIZARD_TIMELINE: String = "wizard-timeline"
const val TAG_WIZARD_ALL_CLEAR: String = "wizard-all-clear"
const val TAG_WIZARD_REMAINING_PREFIX: String = "wizard-remaining-"
