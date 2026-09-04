package to.sava.peranta.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import to.sava.peranta.ui.FixAid
import to.sava.peranta.ui.setPlainText

/** コピー実行直後に添える案内文。 */
private const val COPIED_LABEL: String = "コピーしました"

/** 未確認（[SetupStatus.UNKNOWN]）バッジに添える文言。 */
private const val UNKNOWN_LABEL: String = "未確認"

/**
 * [SetupChecklist] の見せ方。
 * [STANDING] は受信のセットアップ常設画面向けで、全手順を番号・タイトル・状態バッジ付きで一覧する。
 * [IN_PAGE] はウィザードの項目ページ向けの簡約表示で、番号と説明文を省いて操作に絞る。
 */
enum class SetupChecklistMode { STANDING, IN_PAGE }

/**
 * 同一の [SetupItemUi] 列を [mode] に応じた見せ方で描くチェックリスト。
 * [onCopyText] は [FixAid.Copy] のコピー処理で、null なら [LocalClipboard] へフォールバックする。
 */
@Composable
fun SetupChecklist(
    items: List<SetupItemUi>,
    mode: SetupChecklistMode,
    modifier: Modifier = Modifier,
    onCopyText: ((text: String, sensitive: Boolean) -> Unit)? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items.forEachIndexed { index, item ->
            key(item.id) {
                SetupItemRow(item = item, number = index + 1, mode = mode, onCopyText = onCopyText)
            }
        }
    }
}

@Composable
private fun SetupItemRow(
    item: SetupItemUi,
    number: Int,
    mode: SetupChecklistMode,
    onCopyText: ((text: String, sensitive: Boolean) -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("$TAG_SETUP_ITEM_PREFIX${item.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusBadge(status = item.status, modifier = Modifier.testTag("$TAG_SETUP_STATUS_PREFIX${item.id}"))
            val title = if (mode == SetupChecklistMode.STANDING) "$number. ${item.title}" else item.title
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
        }
        if (mode == SetupChecklistMode.STANDING) {
            item.description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item.statusDetail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SetupAids(item = item, onCopyText = onCopyText)
        item.action?.let { action ->
            OutlinedButton(
                onClick = action.run,
                modifier = Modifier.testTag("$TAG_SETUP_ACTION_PREFIX${item.id}"),
            ) {
                Text(text = action.label)
            }
        }
    }
}

/** 状態バッジ。合格 ✓ / 未達 ✗ / 未確認 ─（未確認は文言も添える）。 */
@Composable
private fun StatusBadge(status: SetupStatus, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = badgeGlyph(status),
            color = badgeColor(status),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            modifier = modifier,
        )
        if (status == SetupStatus.UNKNOWN) {
            Text(
                text = UNKNOWN_LABEL,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/**
 * 項目の補助操作を並べる。[FixAid.Copy] は値のコピーチップ、[FixAid.Action] は外部起動などのボタン。
 * コピー後の「コピーしました」は最後にコピーした行にだけ出す（他の行を押すと表示が移動する）。
 */
@Composable
private fun SetupAids(
    item: SetupItemUi,
    onCopyText: ((text: String, sensitive: Boolean) -> Unit)?,
) {
    if (item.aids.isEmpty()) return
    val clipboard = LocalClipboard.current
    val copyScope = rememberCoroutineScope()
    var copiedIndex by remember(item.id) { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item.aids.forEachIndexed { index, aid ->
            when (aid) {
                is FixAid.Copy -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(text = aid.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (copiedIndex == index) {
                        Text(
                            text = COPIED_LABEL,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = {
                            if (onCopyText != null) {
                                onCopyText(aid.value, aid.sensitive)
                            } else {
                                copyScope.launch { clipboard.setPlainText(aid.value) }
                            }
                            copiedIndex = index
                        },
                        modifier = Modifier.testTag("$TAG_SETUP_AID_PREFIX${item.id}-$index"),
                    ) {
                        Text(text = "コピー")
                    }
                }

                is FixAid.Action -> TextButton(
                    onClick = aid.onRun,
                    modifier = Modifier.testTag("$TAG_SETUP_AID_PREFIX${item.id}-$index"),
                ) {
                    Text(text = aid.label)
                }
            }
        }
    }
}

/**
 * 状態を変える操作（主操作・[FixAid.Action]）の実行後に [onActionInvoked] を続けて呼ぶよう包む。
 * 値のコピー（[FixAid.Copy]）は状態を変えないため包まない。受信のセットアップ常設画面とウィザードの
 * 項目ページが、操作直後の自動再チェックのために共用する。
 */
internal fun withRecheckOnAction(items: List<SetupItemUi>, onActionInvoked: () -> Unit): List<SetupItemUi> =
    items.map { item ->
        item.copy(
            aids = item.aids.map { aid ->
                when (aid) {
                    is FixAid.Action -> aid.copy(onRun = { aid.onRun(); onActionInvoked() })
                    is FixAid.Copy -> aid
                }
            },
            action = item.action?.let { action ->
                action.copy(run = { action.run(); onActionInvoked() })
            },
        )
    }

/** 状態を表すマーカー記号。合格 ✓ / 未達 ✗ / 未確認 ─。 */
private fun badgeGlyph(status: SetupStatus): String = when (status) {
    SetupStatus.DONE -> "✓"
    SetupStatus.TODO -> "✗"
    SetupStatus.BLOCKED -> "✗"
    SetupStatus.UNKNOWN -> "─"
}

@Composable
private fun badgeColor(status: SetupStatus): Color = when (status) {
    SetupStatus.DONE -> MaterialTheme.colorScheme.primary
    SetupStatus.TODO -> MaterialTheme.colorScheme.error
    SetupStatus.BLOCKED -> MaterialTheme.colorScheme.error
    SetupStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 項目行コンテナのタグ接頭辞（末尾に item id を付ける）。 */
const val TAG_SETUP_ITEM_PREFIX: String = "setup-item-"

/** 状態バッジのタグ接頭辞（末尾に item id を付ける）。 */
const val TAG_SETUP_STATUS_PREFIX: String = "setup-status-"

/** 主操作ボタンのタグ接頭辞（末尾に item id を付ける）。 */
const val TAG_SETUP_ACTION_PREFIX: String = "setup-action-"

/** 補助操作のタグ接頭辞（末尾に item id + "-" + インデックスを付ける）。 */
const val TAG_SETUP_AID_PREFIX: String = "setup-aid-"
