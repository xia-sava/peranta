package to.sava.peranta.toast

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** トーストを出しているアプリの名前。発信元が判れば「・」でつないで並べる。 */
private const val TOAST_APP_NAME = "Peranta"

/** アプリ名と発信元の区切り。 */
private const val TOAST_CAPTION_SEPARATOR = " ・ "

/** 「開く」ボタンのラベル。本文から URL が抽出できたときだけ出す（§3.3）。 */
private const val OPEN_BUTTON_LABEL = "開く"

/** 既読同期ボタンのラベル。押下は発出元の通知を消すコマンドになる（§3.4）。 */
private const val DISMISS_BUTTON_LABEL = "送信元の通知を消す"

/**
 * 元通知のアクションを並べる段に出すボタンの数の上限（§3.3）。トーストの幅を等分して 1 行に
 * 収めるため、ラベルが読める幅を保てる数で止める。あふれた分はタイムライン（§10.1）で操作する。
 */
private const val MAX_ACTION_BUTTONS = 3

/** 件名に出す最大行数。 */
private const val TITLE_MAX_LINES = 2

/** 本文に出す最大行数。 */
private const val BODY_MAX_LINES = 4

/** 差し込まれた画像の表示高さの上限。トーストが画面を占有しない程度に抑える（§4.3.1）。 */
private val IMAGE_MAX_HEIGHT = 160.dp

/** 件名の左に添える送信者アイコンの一辺（§4.3.1）。 */
private val SENDER_ICON_SIZE = 24.dp

/**
 * トースト本体。周囲のアプリの通知と並んだときに浮かないよう、Windows のトーストに寄せた
 * 配色・角丸・ボタン配置にする。配色は OS の見た目に合わせるため MaterialTheme を通さない。
 */
@Composable
internal fun ToastCard(
    item: ReceivedNotificationToast,
    darkTheme: Boolean,
    onResult: (ToastResult) -> Unit,
) {
    val palette = toastPalette(darkTheme)
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(10.dp, shape)
            .clip(shape)
            .background(palette.surface)
            .border(1.dp, palette.border, shape)
            .clickable { onResult(ToastResult.Clicked) }
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.source?.let { TOAST_APP_NAME + TOAST_CAPTION_SEPARATOR + it } ?: TOAST_APP_NAME,
                color = palette.caption,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            CloseGlyph(palette.caption) { onResult(ToastResult.Dismissed) }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item.senderIcon?.let { icon ->
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(SENDER_ICON_SIZE).clip(CircleShape),
                )
            }
            Text(
                text = item.title,
                color = palette.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = TITLE_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = item.body,
            color = palette.body,
            fontSize = 12.sp,
            maxLines = BODY_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        item.image?.let { image ->
            Spacer(Modifier.height(8.dp))
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = IMAGE_MAX_HEIGHT).clip(RoundedCornerShape(4.dp)),
            )
        }
        Spacer(Modifier.height(12.dp))
        // 元通知のアクションを上段に、この端末の操作（開く・消す）を下段に置く。押す先が
        // 発出元かこの端末かで段を分け、いつも同じ位置に「消す」が来るようにする（§3.3）。
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item.actions.take(MAX_ACTION_BUTTONS)
                .takeIf { it.isNotEmpty() }
                ?.let { actions ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        actions.forEach { action ->
                            ToastButton(action.label, palette, Modifier.weight(1f)) {
                                onResult(ToastResult.ButtonAction(action.index))
                            }
                        }
                    }
                }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.openUrl != null) {
                    ToastButton(OPEN_BUTTON_LABEL, palette, Modifier.weight(1f)) {
                        onResult(ToastResult.ButtonOpen)
                    }
                }
                ToastButton(DISMISS_BUTTON_LABEL, palette, Modifier.weight(1f)) {
                    onResult(ToastResult.ButtonDismiss)
                }
            }
        }
    }
}

/** トーストの配色。 */
private data class ToastPalette(
    val surface: Color,
    val border: Color,
    val title: Color,
    val body: Color,
    val caption: Color,
    val button: Color,
    val buttonBorder: Color,
)

private fun toastPalette(darkTheme: Boolean): ToastPalette =
    if (darkTheme) {
        ToastPalette(
            surface = Color(0xFF2B2B2B),
            border = Color(0xFF3D3D3D),
            title = Color(0xFFFFFFFF),
            body = Color(0xFFCBCBCB),
            caption = Color(0xFF9B9B9B),
            button = Color(0xFF373737),
            buttonBorder = Color(0xFF454545),
        )
    } else {
        ToastPalette(
            surface = Color(0xFFF7F7F7),
            border = Color(0xFFE2E2E2),
            title = Color(0xFF1B1B1B),
            body = Color(0xFF4A4A4A),
            caption = Color(0xFF767676),
            button = Color(0xFFFCFCFC),
            buttonBorder = Color(0xFFD8D8D8),
        )
    }

@Composable
private fun ToastButton(
    label: String,
    palette: ToastPalette,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(palette.button)
            .border(1.dp, palette.buttonBorder, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = palette.title, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CloseGlyph(color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = "✕", color = color, fontSize = 11.sp)
    }
}
