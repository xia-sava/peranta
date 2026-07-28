package to.sava.peranta.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import to.sava.peranta.pairing.SettingsController
import to.sava.peranta.ui.setup.DeviceNameField
import to.sava.peranta.ui.setup.HostPortFields
import to.sava.peranta.ui.setup.KeyStatusText
import to.sava.peranta.ui.setup.LabeledCheckbox
import to.sava.peranta.ui.setup.PairingQrSection
import to.sava.peranta.ui.setup.SMS_DIRECT_RECEIVE_DESCRIPTION
import to.sava.peranta.ui.setup.SetupItemUi
import to.sava.peranta.ui.setup.SetupOverviewRow
import to.sava.peranta.ui.setup.SetupOverviewStatus
import to.sava.peranta.ui.setup.SetupOverviewTarget
import to.sava.peranta.ui.setup.TIMELINE_RETENTION_DAYS_DESCRIPTION
import to.sava.peranta.ui.setup.TimelineRetentionDaysField
import to.sava.peranta.ui.setup.TokenField
import to.sava.peranta.ui.setup.setupOverview
import to.sava.peranta.update.UpdateController
import to.sava.peranta.update.UpdateInstallState
import to.sava.peranta.update.UpdateStatus

/** QR の自動非表示までの既定時間（§6: 表示は時間制限つき）。 */
private const val DEFAULT_QR_VISIBLE_MILLIS: Long = 60_000L

/** 鍵を作り直すと全端末で QR の読み直しが必要になる旨の警告文（§6）。 */
private const val ROTATE_WARNING_BODY: String =
    "前の鍵は破棄され、全端末で QR の読み直しが必要になります。続けますか？"

/** 更新の適用でアプリが一度終了する旨を断る文（§12）。 */
private const val APPLY_CONFIRM_BODY: String =
    "アプリを終了して更新を適用し、そのあと自動で起動し直します。適用のあいだは受信が止まります。"

/** 危険な操作セクションで共有鍵の作り直しの影響を説明する文（§6）。 */
private const val ROTATE_DANGER_DESCRIPTION: String =
    "作り直すと前の鍵は使えなくなり、全端末で新しい QR の読み直しが必要になります。"

/** 共有鍵を作り直した直後に、配布のため QR の読み取りを促す文（§6）。 */
private const val ROTATE_DISTRIBUTE_MESSAGE: String =
    "新しい鍵を作成しました。下の QR を各端末で読み取ってください。"

/** 危険な操作セクションで全消去の影響を説明する文（§11）。 */
private const val RESET_DANGER_DESCRIPTION: String =
    "接続設定・共有鍵・端末名・受信した履歴を全て消し、インストール直後の状態に戻します。"

/** 全消去の確認で、消える対象とその後の手順を断る文（§11）。 */
private const val RESET_WARNING_BODY: String =
    "接続設定・共有鍵・端末名・受信した履歴と添付が全て消えます。取り消せません。" +
        "消したあとアプリを終了するので、次に起動すると初期設定から始まります。" +
        "他の端末とつなぎ直すには QR の読み取りが改めて必要です。"

/** フラット画面で変更が自動保存される旨を伝える説明文。 */
private const val AUTOSAVE_NOTE: String = "変更は自動的に保存され、この画面を離れたときに反映されます。"

/** センシティブ通知の履歴保存トグルの説明文（§11: 既定 OFF が安全側）。 */
private const val PERSIST_SENSITIVE_HISTORY_DESCRIPTION: String =
    "OFF のままだと OTP 等の本文はタイムラインに残しません。"

/** 画像の自動表示トグルの説明文（§4.3）。 */
private const val AUTO_DISPLAY_IMAGES_DESCRIPTION: String =
    "受信した画像を自動でダウンロードして表示します。通信量が気になる場合は OFF にしてください。"

/** 通知画像の転送トグルの説明文（§4.3.1）。 */
private const val ATTACH_NOTIFICATION_IMAGES_DESCRIPTION: String =
    "この端末が転送する通知に、通知に付いていた画像と送信者のアイコンを添えます。" +
        "通信量が気になる場合は OFF にしてください。"

/** 自動起動トグルの説明文（§3.3）。 */
private const val AUTO_START_DESCRIPTION: String =
    "サインインしたときに Peranta を起動し、タスクトレイに常駐して受信を始めます。" +
        "ウィンドウは開かないので、見るときはトレイのアイコンから開いてください。"

/** 開発実行では自動起動を登録できない旨の注記（§3.3）。 */
private const val AUTO_START_DEV_BUILD_NOTE: String =
    "開発ビルドでは設定できません（配布版のみ）。"

/** 開発ビルドでは更新を適用できない旨の注記（§12）。 */
private const val UPDATE_DEV_BUILD_NOTE: String =
    "開発ビルドでは更新できません（配布版のみ）。"

/** 共有鍵・トークン未設定で QR を作れないときの案内文。 */
private const val PAIRING_PREREQUISITE_NOTICE: String = "先にトークンと共有鍵を設定してください。"

/** 共有鍵は作成できたが接続設定不足で QR を作れないときの案内文。 */
private const val KEY_CREATED_QR_PREREQUISITE_NOTICE: String =
    "QR の表示には接続設定が必要です。先にサーバホスト名とアクセストークンを設定してください。"

private const val SECTION_SETUP_OVERVIEW: String = "セットアップ状況"
private const val SECTION_CONNECTION: String = "ntfyサーバー接続設定"
private const val SECTION_THIS_DEVICE: String = "この端末"
private const val SECTION_NOTIFICATIONS: String = "通知と履歴"
private const val SECTION_THIS_PC: String = "この PC での動作"
private const val SECTION_UPDATE: String = "アプリの更新"
private const val SECTION_ADD_DEVICE: String = "端末の追加"
private const val SECTION_DANGER: String = "危険な操作"

/**
 * 設定画面（§10.2）とペアリング（§10.3）を単一スクロールのセクション構造で編集するフラット画面。
 * 接続情報の入力・保存、共有鍵の作成、QR による新端末追加、共有鍵の作り直しを行う。入力・操作は
 * 全て即時保存し、画面を離れたときに反映する。
 *
 * QR の描画・スクロールバー・ペアリング文字列コピーはプラットフォーム依存のため
 * [qrContent] / [scrollbarContent] / [onCopyPairingUri] スロットで注入する。
 * [showSendRoleOptions] が真のときだけ送信ロール（[to.sava.peranta.config.PerantaConfig.sendEnabled] /
 * [to.sava.peranta.config.PerantaConfig.smsDirectReceive]）のトグルを表示する。
 * [onSaved] は設定の保存・鍵生成が成功した直後に呼ぶ（受信パイプラインの再構築契機に使う）。
 * [onOpenWizard] が非 null のとき、セットアップをページ列で案内するウィザードへの導線を出す。
 * [loadHealthItems] が非 null のとき冒頭に「セットアップ状況」セクションを出し、初回コンポジションで一度だけ
 * 動作チェック項目を取得して集計する（取得中は未確認表示）。[hasReceiveSetup] が真なら受信経路の行も出し、
 * [loadReceiveSetupItems] で受信のセットアップ項目を取得する。[onOpenHealthCheck] / [onOpenReceiveSetup] /
 * [onOpenPairingImport] は各行の導線で、null なら該当行の導線を出さない。
 * [update] が非 null のとき「アプリの更新」セクションを出し、ボタン押下時だけ更新確認を実行する（§12）。
 * [autoStart] が非 null のとき「この PC での動作」セクションを出し、ログオン時自動起動の登録を扱う（§3.3）。
 * [showHeader] が false のときは画面見出し行（タイトルと「タイムラインへ」）を出さない。外側のアプリバーが
 * 見出しと戻る導線を持つ埋め込み利用で使い、既定の true では従来どおり見出しつきの単独画面として振る舞う。
 */
@Composable
fun SettingsScreen(
    controller: SettingsController,
    modifier: Modifier = Modifier,
    qrContent: @Composable (uri: String) -> Unit = {},
    onOpenTimeline: (() -> Unit)? = null,
    qrVisibleMillis: Long = DEFAULT_QR_VISIBLE_MILLIS,
    scrollbarContent: @Composable BoxScope.(scrollState: ScrollState) -> Unit = {},
    onCopyPairingUri: ((String) -> Unit)? = null,
    showSendRoleOptions: Boolean = false,
    onSaved: (() -> Unit)? = null,
    onOpenWizard: (() -> Unit)? = null,
    loadHealthItems: (suspend () -> List<HealthCheckItem>)? = null,
    onOpenHealthCheck: (() -> Unit)? = null,
    onOpenPairingImport: (() -> Unit)? = null,
    hasReceiveSetup: Boolean = false,
    loadReceiveSetupItems: (suspend () -> List<SetupItemUi>)? = null,
    onOpenReceiveSetup: (() -> Unit)? = null,
    update: UpdateUi? = null,
    autoStart: AutoStartUi? = null,
    showHeader: Boolean = true,
    onResetAll: (() -> Unit)? = null,
) {
    val initial = remember { controller.load() }
    var host by remember { mutableStateOf(initial.host) }
    var accessToken by remember { mutableStateOf(initial.accessToken.orEmpty()) }
    var deviceName by remember { mutableStateOf(initial.deviceName.orEmpty()) }
    var port by remember { mutableStateOf(initial.port?.toString().orEmpty()) }
    var keyId by remember { mutableStateOf(initial.keyId) }
    var hasKey by remember { mutableStateOf(!initial.sharedKeyBase64.isNullOrBlank()) }
    var persistSensitiveHistory by remember { mutableStateOf(initial.persistSensitiveHistory) }
    var attachFullTextWhenTruncated by remember { mutableStateOf(initial.attachFullTextWhenTruncated) }
    var attachNotificationImages by remember { mutableStateOf(initial.attachNotificationImages) }
    var timelineRetentionDays by remember { mutableStateOf(initial.timelineRetentionDays?.toString().orEmpty()) }
    var autoDisplayImages by remember { mutableStateOf(initial.autoDisplayImages) }
    var sendEnabled by remember { mutableStateOf(initial.sendEnabled) }
    var smsDirectReceive by remember { mutableStateOf(initial.smsDirectReceive) }
    var autoStartEnabled by remember { mutableStateOf(autoStart?.isEnabled?.invoke() ?: false) }

    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showRotateWarning by remember { mutableStateOf(false) }
    var showResetWarning by remember { mutableStateOf(false) }
    var pairingUri by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var dangerExpanded by rememberSaveable { mutableStateOf(false) }
    var overviewHealthItems by remember { mutableStateOf<List<HealthCheckItem>?>(null) }
    var overviewReceiveItems by remember { mutableStateOf<List<SetupItemUi>?>(null) }

    // 動作チェック項目は送信ロールのトグルに応じて顔ぶれが変わるため、トグル変更のたびに取り直して
    // 同一画面内のセットアップ状況を実態に追従させる。
    if (loadHealthItems != null) {
        LaunchedEffect(sendEnabled, smsDirectReceive) { overviewHealthItems = loadHealthItems() }
    }
    if (loadReceiveSetupItems != null) {
        LaunchedEffect(Unit) { overviewReceiveItems = loadReceiveSetupItems() }
    }

    fun persistConnection() {
        controller.saveConnectionSettings(
            host = host,
            accessToken = accessToken,
            deviceName = deviceName,
            port = port.toIntOrNull(),
            persistSensitiveHistory = persistSensitiveHistory,
            attachFullTextWhenTruncated = attachFullTextWhenTruncated,
            timelineRetentionDays = timelineRetentionDays.toIntOrNull(),
            autoDisplayImages = autoDisplayImages,
            attachNotificationImages = attachNotificationImages,
        )
        dirty = true
    }

    /** ペアリング URI を採番して QR 表示状態にする。作れないときは案内文だけ出す。 */
    fun showPairingQr() {
        val uri = controller.buildPairingUri()
        pairingUri = uri
        if (uri == null) statusMessage = PAIRING_PREREQUISITE_NOTICE
    }

    /** 共有鍵を作成（作り直し）し、続けて QR を表示する。作成できても QR を作れなければ案内文を出す。 */
    fun rotateKeyAndShowQr(successMessage: String?) {
        val updated = controller.rotateSharedKey()
        keyId = updated.keyId
        hasKey = true
        onSaved?.invoke()
        val uri = controller.buildPairingUri()
        pairingUri = uri
        statusMessage = if (uri == null) KEY_CREATED_QR_PREREQUISITE_NOTICE else successMessage
    }

    LaunchedEffect(pairingUri) {
        if (pairingUri == null) return@LaunchedEffect
        delay(qrVisibleMillis)
        pairingUri = null
    }

    DisposableEffect(Unit) {
        onDispose {
            if (dirty) onSaved?.invoke()
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val scrollState = rememberScrollState()
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (showHeader) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = "設定", style = MaterialTheme.typography.titleLarge)
                        if (onOpenTimeline != null) {
                            TextButton(
                                onClick = {
                                    if (dirty) {
                                        onSaved?.invoke()
                                        dirty = false
                                    }
                                    onOpenTimeline()
                                },
                            ) { Text(text = "タイムラインへ") }
                        }
                    }
                }
                Text(
                    text = AUTOSAVE_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(TAG_AUTOSAVE_NOTE),
                )
                if (loadHealthItems != null) {
                    SetupOverviewSection(
                        rows = setupOverview(
                            hasHost = host.isNotBlank(),
                            hasToken = accessToken.isNotBlank(),
                            hasSharedKey = hasKey,
                            healthItems = overviewHealthItems,
                            hasReceiveSetup = hasReceiveSetup,
                            receiveSetupItems = overviewReceiveItems,
                        ),
                        onOpenHealthCheck = onOpenHealthCheck,
                        onOpenReceiveSetup = onOpenReceiveSetup,
                        onOpenPairingImport = onOpenPairingImport,
                    )
                }
                if (onOpenWizard != null) {
                    TextButton(
                        onClick = onOpenWizard,
                        modifier = Modifier.testTag(TAG_OPEN_WIZARD),
                    ) { Text(text = "ウィザードで最初からやり直す") }
                }

                SectionHeader(title = SECTION_CONNECTION)
                HostPortFields(
                    host = host,
                    port = port,
                    onHostChange = { host = it; persistConnection() },
                    onPortChange = { port = it; persistConnection() },
                )
                TokenField(value = accessToken, onValueChange = { accessToken = it; persistConnection() })

                SectionHeader(title = SECTION_THIS_DEVICE)
                DeviceNameField(value = deviceName, onValueChange = { deviceName = it; persistConnection() })
                if (showSendRoleOptions) {
                    LabeledCheckbox(
                        checked = sendEnabled,
                        onCheckedChange = {
                            sendEnabled = it
                            controller.saveSendRoleSettings(sendEnabled, smsDirectReceive)
                            dirty = true
                        },
                        label = "この端末から通知・SMS を送信する",
                        tag = TAG_SEND_ENABLED,
                    )
                    LabeledCheckbox(
                        checked = smsDirectReceive,
                        onCheckedChange = {
                            smsDirectReceive = it
                            controller.saveSendRoleSettings(sendEnabled, smsDirectReceive)
                            dirty = true
                        },
                        label = "SMS を直接受信して転送する",
                        tag = TAG_SMS_DIRECT_RECEIVE,
                    )
                    Text(
                        text = SMS_DIRECT_RECEIVE_DESCRIPTION,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SectionHeader(title = SECTION_NOTIFICATIONS)
                LabeledCheckbox(
                    checked = persistSensitiveHistory,
                    onCheckedChange = { persistSensitiveHistory = it; persistConnection() },
                    label = "センシティブな通知の本文を履歴に保存する",
                    tag = TAG_PERSIST_SENSITIVE,
                )
                Text(
                    text = PERSIST_SENSITIVE_HISTORY_DESCRIPTION,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LabeledCheckbox(
                    checked = attachFullTextWhenTruncated,
                    onCheckedChange = { attachFullTextWhenTruncated = it; persistConnection() },
                    label = "長文本文の全文をシームレスに添付・展開する",
                    tag = TAG_ATTACH_FULL_TEXT,
                )
                LabeledCheckbox(
                    checked = attachNotificationImages,
                    onCheckedChange = { attachNotificationImages = it; persistConnection() },
                    label = "通知の画像も転送する",
                    tag = TAG_ATTACH_NOTIFICATION_IMAGES,
                )
                Text(
                    text = ATTACH_NOTIFICATION_IMAGES_DESCRIPTION,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TimelineRetentionDaysField(
                    value = timelineRetentionDays,
                    onValueChange = { timelineRetentionDays = it; persistConnection() },
                )
                Text(
                    text = TIMELINE_RETENTION_DAYS_DESCRIPTION,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LabeledCheckbox(
                    checked = autoDisplayImages,
                    onCheckedChange = { autoDisplayImages = it; persistConnection() },
                    label = "画像を自動表示",
                    tag = TAG_AUTO_DISPLAY_IMAGES,
                )
                Text(
                    text = AUTO_DISPLAY_IMAGES_DESCRIPTION,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (autoStart != null) {
                    SectionHeader(title = SECTION_THIS_PC)
                    LabeledCheckbox(
                        checked = autoStartEnabled,
                        onCheckedChange = { autoStartEnabled = it; autoStart.onChange(it) },
                        label = "サインイン時に自動起動する",
                        tag = TAG_AUTO_START,
                        enabled = autoStart.editable,
                    )
                    Text(
                        text = AUTO_START_DESCRIPTION,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (autoStart.unavailableInDevBuild) {
                        Text(
                            text = AUTO_START_DEV_BUILD_NOTE,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (update != null) {
                    SectionHeader(title = SECTION_UPDATE)
                    UpdateSection(update)
                }

                SectionHeader(title = SECTION_ADD_DEVICE)
                KeyStatusText(hasKey = hasKey, keyId = keyId)
                if (hasKey) {
                    OutlinedButton(
                        onClick = { showPairingQr() },
                        modifier = Modifier.testTag(TAG_ADD_DEVICE),
                    ) {
                        Text(text = "端末追加用のQRを表示")
                    }
                } else {
                    OutlinedButton(
                        onClick = { rotateKeyAndShowQr(successMessage = null) },
                        modifier = Modifier.testTag(TAG_CREATE_KEY),
                    ) {
                        Text(text = "共有鍵を作成して QR を表示")
                    }
                }

                statusMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag(TAG_STATUS),
                    )
                }

                pairingUri?.let { uri ->
                    PairingQrSection(
                        uri = uri,
                        qrContent = qrContent,
                        onCopyPairingUri = onCopyPairingUri,
                        onCopied = { statusMessage = "ペアリング文字列をコピーしました。" },
                        onHide = { pairingUri = null },
                    )
                }

                if (hasKey || onResetAll != null) {
                    DangerSectionHeader(
                        expanded = dangerExpanded,
                        onToggle = { dangerExpanded = !dangerExpanded },
                    )
                    if (dangerExpanded && hasKey) {
                        Text(
                            text = ROTATE_DANGER_DESCRIPTION,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = { showRotateWarning = true },
                            modifier = Modifier.testTag(TAG_ROTATE),
                        ) {
                            Text(text = "共有鍵を作り直す")
                        }
                    }
                    if (dangerExpanded && onResetAll != null) {
                        Text(
                            text = RESET_DANGER_DESCRIPTION,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = { showResetWarning = true },
                            modifier = Modifier.testTag(TAG_RESET),
                        ) {
                            Text(text = "すべての情報を消去する")
                        }
                    }
                }
            }
            scrollbarContent(scrollState)
        }
    }

    if (showRotateWarning) {
        AlertDialog(
            onDismissRequest = { showRotateWarning = false },
            title = { Text(text = "鍵を作り直しますか？") },
            text = { Text(text = ROTATE_WARNING_BODY) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRotateWarning = false
                        rotateKeyAndShowQr(ROTATE_DISTRIBUTE_MESSAGE)
                    },
                    modifier = Modifier.testTag(TAG_ROTATE_CONFIRM),
                ) {
                    Text(text = "破棄して作成")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRotateWarning = false }) {
                    Text(text = "やめる")
                }
            },
        )
    }

    if (showResetWarning && onResetAll != null) {
        AlertDialog(
            onDismissRequest = { showResetWarning = false },
            title = { Text(text = "すべての情報を消去しますか？") },
            text = { Text(text = RESET_WARNING_BODY) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetWarning = false
                        onResetAll()
                    },
                    modifier = Modifier.testTag(TAG_RESET_CONFIRM),
                ) {
                    Text(text = "消去して終了")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetWarning = false }) {
                    Text(text = "やめる")
                }
            },
        )
    }
}

/**
 * 「セットアップ状況」セクション（§10.2）。機能単位の行で「何を設定するのに何が必要か」の全体地図を示す。
 * 各行は状態バッジ・事実記述と、誘導先を持つ行の [開く] 導線を並べる。[開く] のコールバックが null の
 * 行では導線を出さない（プラットフォーム差の吸収）。
 */
@Composable
private fun SetupOverviewSection(
    rows: List<SetupOverviewRow>,
    onOpenHealthCheck: (() -> Unit)?,
    onOpenReceiveSetup: (() -> Unit)?,
    onOpenPairingImport: (() -> Unit)?,
) {
    SectionHeader(title = SECTION_SETUP_OVERVIEW)
    rows.forEach { row ->
        val onOpen = when (row.target) {
            SetupOverviewTarget.HealthCheck -> onOpenHealthCheck
            SetupOverviewTarget.ReceiveSetup -> onOpenReceiveSetup
            SetupOverviewTarget.PairingImport -> onOpenPairingImport
            null -> null
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = overviewMarker(row.status),
                color = overviewMarkerColor(row.status),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .testTag("$TAG_OVERVIEW_STATE_PREFIX${row.id}")
                    .padding(end = 12.dp),
            )
            Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(text = row.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                row.detail?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val openLabel = row.openLabel
            if (openLabel != null && onOpen != null) {
                TextButton(
                    onClick = onOpen,
                    modifier = Modifier.testTag("$TAG_OVERVIEW_OPEN_PREFIX${row.id}"),
                ) {
                    Text(text = openLabel)
                }
            }
        }
    }
}

/** セットアップ状況の状態マーカー記号。達成 ✓ / 未達 ✗ / 未確認 ?。 */
private fun overviewMarker(status: SetupOverviewStatus): String = when (status) {
    SetupOverviewStatus.MET -> "✓"
    SetupOverviewStatus.UNMET -> "✗"
    SetupOverviewStatus.UNKNOWN -> "?"
}

@Composable
private fun overviewMarkerColor(status: SetupOverviewStatus): Color = when (status) {
    SetupOverviewStatus.MET -> MaterialTheme.colorScheme.primary
    SetupOverviewStatus.UNMET -> MaterialTheme.colorScheme.error
    SetupOverviewStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** セクションの見出し（ラベル＋区切り線）。[color] で見出しの色を変えられる（危険な操作はエラー色）。 */
@Composable
private fun SectionHeader(title: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier.padding(top = 8.dp),
    )
    HorizontalDivider()
}

/**
 * 「危険な操作」セクションの見出し（エラー色）。クリックで [expanded] の開閉を切り替える。
 * 折り畳み状態は矢印で示す。
 */
@Composable
private fun DangerSectionHeader(expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clickable(onClick = onToggle)
            .testTag(TAG_DANGER_TOGGLE),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = if (expanded) "▼" else "▶",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = SECTION_DANGER,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
        )
    }
    HorizontalDivider()
}

/**
 * 「アプリの更新」セクションの中身。ボタン押下時だけ更新確認を実行し、結果をボタンの下に
 * 表示する（§12: 起動時の自動確認は行わない）。ダウンロード中は受信量を進捗として出し、
 * 適用の確認を持つプラットフォームでは照合の完了後に確認を挟む。
 */
@Composable
private fun UpdateSection(update: UpdateUi) {
    val status by update.controller.status.collectAsState()
    val checking by update.controller.checking.collectAsState()
    val installState = update.installState
    update.currentVersionName?.let { version ->
        Text(
            text = "現在のバージョン $version",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(TAG_CURRENT_VERSION),
        )
    }
    OutlinedButton(
        onClick = { update.controller.checkNow() },
        enabled = !checking && update.canUpdate,
        modifier = Modifier.testTag(TAG_UPDATE_CHECK),
    ) {
        Text(text = if (checking) "確認中..." else "アプリ更新チェック")
    }
    if (!update.canUpdate) {
        Text(
            text = UPDATE_DEV_BUILD_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(TAG_UPDATE_DEV_BUILD_NOTE),
        )
    }
    updateStatusText(status)?.let { text ->
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (status is UpdateStatus.Failed) MaterialTheme.colorScheme.error else Color.Unspecified,
            modifier = Modifier.testTag(TAG_UPDATE_STATUS),
        )
    }
    val available = status as? UpdateStatus.Available
    val onInstall = update.onInstall
    if (available != null && onInstall != null) {
        OutlinedButton(
            onClick = { onInstall(available) },
            enabled = !isInstallRunning(installState),
            modifier = Modifier.testTag(TAG_UPDATE_INSTALL),
        ) {
            Text(text = "ダウンロードして更新")
        }
    }
    updateInstallText(installState)?.let { text ->
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (installState is UpdateInstallState.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                Color.Unspecified
            },
            modifier = Modifier.testTag(TAG_UPDATE_INSTALL_STATE),
        )
    }
    if (installState is UpdateInstallState.Downloading) {
        DownloadProgress(installState)
    }
    if (installState == UpdateInstallState.ReadyToApply && update.onApply != null) {
        ApplyConfirmDialog(onApply = update.onApply, onCancel = update.onCancelApply)
    }
}

/** ダウンロードの進み具合。全体長が判らないうちは長さの決まらない表示にする。 */
@Composable
private fun DownloadProgress(state: UpdateInstallState.Downloading) {
    val fraction = state.fraction
    if (fraction == null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag(TAG_UPDATE_PROGRESS))
    } else {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().testTag(TAG_UPDATE_PROGRESS),
        )
    }
}

/**
 * 適用の確認。適用するとアプリが終了して更新後に起動し直すため、常駐が途切れることを
 * 断ってから進める。取りやめるとダウンロード済みの配布物は捨てる。
 */
@Composable
private fun ApplyConfirmDialog(onApply: () -> Unit, onCancel: (() -> Unit)?) {
    AlertDialog(
        onDismissRequest = { onCancel?.invoke() },
        title = { Text(text = "更新を適用しますか？") },
        text = { Text(text = APPLY_CONFIRM_BODY) },
        confirmButton = {
            TextButton(
                onClick = onApply,
                modifier = Modifier.testTag(TAG_UPDATE_APPLY_CONFIRM),
            ) {
                Text(text = "再起動して適用")
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onCancel?.invoke() },
                modifier = Modifier.testTag(TAG_UPDATE_APPLY_CANCEL),
            ) {
                Text(text = "あとで")
            }
        },
        modifier = Modifier.testTag(TAG_UPDATE_APPLY_DIALOG),
    )
}

/** 更新確認結果の表示文。未チェック（null）は結果行を出さない。 */
private fun updateStatusText(status: UpdateStatus?): String? = when (status) {
    null -> null
    UpdateStatus.UpToDate -> "最新のバージョンです"
    is UpdateStatus.Available -> "新しいバージョン ${status.versionName}"
    is UpdateStatus.Failed -> "更新確認に失敗しました: ${status.reason}"
}

/** 適用の進み具合の表示文。未着手（null）は行を出さない。 */
private fun updateInstallText(state: UpdateInstallState?): String? = when (state) {
    null -> null
    is UpdateInstallState.Downloading -> downloadProgressText(state)
    UpdateInstallState.Verifying -> "照合中..."
    UpdateInstallState.ReadyToApply -> "更新の準備ができました"
    UpdateInstallState.Launching -> "インストーラを起動しました"
    is UpdateInstallState.Failed -> state.reason
}

/** ダウンロードの受信量。全体長が判らなければ受信量だけを出す。 */
private fun downloadProgressText(state: UpdateInstallState.Downloading): String =
    if (state.totalBytes > 0) {
        "ダウンロード中... ${formatFileSize(state.receivedBytes)} / ${formatFileSize(state.totalBytes)}"
    } else {
        "ダウンロード中... ${formatFileSize(state.receivedBytes)}"
    }

/** 適用が進行中か。進行中は適用ボタンを押せなくする。 */
private fun isInstallRunning(state: UpdateInstallState?): Boolean = when (state) {
    is UpdateInstallState.Downloading -> true
    UpdateInstallState.Verifying, UpdateInstallState.ReadyToApply, UpdateInstallState.Launching -> true
    else -> false
}

const val TAG_AUTOSAVE_NOTE: String = "settings-autosave-note"
const val TAG_OPEN_WIZARD: String = "settings-open-wizard"
const val TAG_OVERVIEW_STATE_PREFIX: String = "settings-overview-state-"
const val TAG_OVERVIEW_OPEN_PREFIX: String = "settings-overview-open-"
const val TAG_HOST: String = "settings-host"
const val TAG_TOKEN: String = "settings-token"
const val TAG_DEVICE_NAME: String = "settings-deviceName"
const val TAG_PORT: String = "settings-port"
const val TAG_PERSIST_SENSITIVE: String = "settings-persist-sensitive"
const val TAG_ATTACH_FULL_TEXT: String = "settings-attach-full-text"
const val TAG_TIMELINE_RETENTION_DAYS: String = "settings-timeline-retention-days"
const val TAG_AUTO_DISPLAY_IMAGES: String = "settings-auto-display-images"

/** 通知画像の転送トグルのテストタグ。 */
const val TAG_ATTACH_NOTIFICATION_IMAGES: String = "settings-attach-notification-images"

/** 自動起動トグルのテストタグ。 */
const val TAG_AUTO_START: String = "settings-auto-start"
const val TAG_SEND_ENABLED: String = "settings-send-enabled"
const val TAG_SMS_DIRECT_RECEIVE: String = "settings-sms-direct-receive"
const val TAG_CURRENT_VERSION: String = "settings-current-version"
const val TAG_UPDATE_CHECK: String = "settings-update-check"
const val TAG_UPDATE_DEV_BUILD_NOTE: String = "settings-update-dev-build-note"
const val TAG_UPDATE_STATUS: String = "settings-update-status"
const val TAG_UPDATE_INSTALL: String = "settings-update-install"
const val TAG_UPDATE_INSTALL_STATE: String = "settings-update-install-state"
const val TAG_UPDATE_PROGRESS: String = "settings-update-progress"
const val TAG_UPDATE_APPLY_DIALOG: String = "settings-update-apply-dialog"
const val TAG_UPDATE_APPLY_CONFIRM: String = "settings-update-apply-confirm"
const val TAG_UPDATE_APPLY_CANCEL: String = "settings-update-apply-cancel"
const val TAG_CREATE_KEY: String = "settings-create-key"
const val TAG_DANGER_TOGGLE: String = "settings-danger-toggle"
const val TAG_ROTATE: String = "settings-rotate"
const val TAG_ROTATE_CONFIRM: String = "settings-rotate-confirm"
const val TAG_RESET: String = "settings-reset"
const val TAG_RESET_CONFIRM: String = "settings-reset-confirm"
const val TAG_ADD_DEVICE: String = "settings-add-device"
const val TAG_HIDE_QR: String = "settings-hide-qr"
const val TAG_COPY_PAIRING_URI: String = "settings-copy-pairing-uri"
const val TAG_STATUS: String = "settings-status"
