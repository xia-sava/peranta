package to.sava.peranta.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.roster.RosterEntry
import to.sava.peranta.roster.RosterFetchResult
import to.sava.peranta.ui.formatRelativeTime

/**
 * 参加端末一覧ドロップダウンの取得口。プラットフォームが組んでシェルの serverTrailing へ注入する。
 * [selfDeviceId] は一覧上で自端末を「（この端末）」と示すために使う。
 */
class RosterUi(
    val selfDeviceId: String?,
    val fetch: suspend () -> RosterFetchResult,
)

/**
 * 一覧の表示順を決める。自端末を先頭に、残りは deviceName 昇順（同名は deviceId 昇順で安定化）。
 */
fun rosterDisplayOrder(entries: List<RosterEntry>, selfDeviceId: String?): List<RosterEntry> =
    entries.sortedWith(compareBy({ it.deviceId != selfDeviceId }, { it.deviceName }, { it.deviceId }))

/**
 * アプリバーのサーバ名の隣に置く参加端末一覧ドロップダウン（§2）。トグル（▾）をタップすると
 * [RosterUi.fetch] を呼んで一覧を取得し、取得中/失敗/0 件/一覧のいずれかを表示する。
 * キャッシュはせず、開くたびに取得し直す。行は表示専用でクリックアクションを持たない。
 */
@Composable
fun RosterDropdown(roster: RosterUi, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<RosterFetchResult?>(null) }
    var fetchedAt by remember { mutableStateOf(0L) }

    LaunchedEffect(expanded) {
        if (expanded) {
            result = null
            result = roster.fetch()
            fetchedAt = nowEpochMillis()
        }
    }

    Box(modifier = modifier) {
        Text(
            text = "▾",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable { expanded = true }
                .testTag(TAG_ROSTER_TOGGLE)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RosterMenuContent(result = result, selfDeviceId = roster.selfDeviceId, nowEpochMillis = fetchedAt)
        }
    }
}

/** メニューの中身。取得中/失敗/0 件/一覧のいずれかを描く。 */
@Composable
private fun RosterMenuContent(result: RosterFetchResult?, selfDeviceId: String?, nowEpochMillis: Long) {
    when (result) {
        null -> RosterMessage(text = "取得中…", tag = TAG_ROSTER_LOADING)
        RosterFetchResult.FetchFailed -> RosterMessage(text = "参加端末を取得できませんでした", tag = TAG_ROSTER_ERROR)
        is RosterFetchResult.Fetched -> {
            val ordered = rosterDisplayOrder(result.entries, selfDeviceId)
            if (ordered.isEmpty()) {
                RosterMessage(text = "参加端末がまだありません", tag = TAG_ROSTER_EMPTY)
            } else {
                ordered.forEach { entry ->
                    RosterRow(entry = entry, selfDeviceId = selfDeviceId, nowEpochMillis = nowEpochMillis)
                }
            }
        }
    }
}

@Composable
private fun RosterMessage(text: String, tag: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag(tag).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** 端末 1 件の行（縦 2 段）。表示専用でクリックアクションは持たない。 */
@Composable
private fun RosterRow(entry: RosterEntry, selfDeviceId: String?, nowEpochMillis: Long) {
    val displayName = if (entry.deviceId == selfDeviceId) "${entry.deviceName}（この端末）" else entry.deviceName
    Column(
        modifier = Modifier
            .testTag("$TAG_ROSTER_ITEM_PREFIX${entry.deviceId}")
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text = displayName, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = formatRelativeTime(nowEpochMillis, entry.lastUpdatedEpochMillis),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** ドロップダウン開閉トグル（▾）のタグ。 */
const val TAG_ROSTER_TOGGLE: String = "shell-roster-toggle"

/** 取得中表示のタグ。 */
const val TAG_ROSTER_LOADING: String = "shell-roster-loading"

/** 取得失敗表示のタグ。 */
const val TAG_ROSTER_ERROR: String = "shell-roster-error"

/** 取得成功・0 件表示のタグ。 */
const val TAG_ROSTER_EMPTY: String = "shell-roster-empty"

/** 端末行のタグ接頭辞（末尾に deviceId を付ける）。 */
const val TAG_ROSTER_ITEM_PREFIX: String = "shell-roster-item-"
