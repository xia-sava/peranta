package to.sava.peranta.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration

/** http(s) URL を検出する正規表現。スキーム直後に続く連続した非空白文字列を URL 候補とする。 */
private val URL_PATTERN: Regex = Regex("https?://\\S+")

/** URL 候補の末尾から取り除く閉じ括弧・句読点。 */
private val TRAILING_PUNCTUATION: Set<Char> = setOf(')', '。', '、')

/**
 * [text] に含まれる http(s) URL の範囲一覧を検出する（純関数）。
 * `https?://` で始まる連続した非空白文字列を URL 候補とし、末尾の閉じ括弧・句読点
 * （[TRAILING_PUNCTUATION]）は URL に含めない。取り除いた結果 URL 部分が残らなければ検出しない。
 */
internal fun findUrlRanges(text: String): List<IntRange> =
    URL_PATTERN.findAll(text).mapNotNull { match ->
        var end = match.range.last
        while (end >= match.range.first && text[end] in TRAILING_PUNCTUATION) end--
        if (end < match.range.first) null else match.range.first..end
    }.toList()

/**
 * 本文中の URL をリンク化して表示する（タイムラインの通常テキスト・通知/SMS 本文・ファイルの
 * キャプションで共通に使う）。[findUrlRanges] で見つけた URL 部分に下線付きの
 * [MaterialTheme.colorScheme.primary] を当て、[LinkAnnotation.Url]（Compose 標準機構）が
 * [androidx.compose.ui.platform.LocalUriHandler] 経由でタップ/クリックを既定ブラウザ起動へ自動で流す。
 * リンクのタップ領域は URL 文字列に限られるため、バブル全体のスワイプ等の既存ジェスチャとは干渉しない。
 */
@Composable
internal fun LinkifiedText(
    text: String,
    style: TextStyle = LocalTextStyle.current,
    modifier: Modifier = Modifier,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, linkColor) {
        buildAnnotatedString {
            append(text)
            val linkStyles = TextLinkStyles(
                style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
            )
            findUrlRanges(text).forEach { range ->
                addLink(
                    url = LinkAnnotation.Url(text.substring(range.first, range.last + 1), linkStyles),
                    start = range.first,
                    end = range.last + 1,
                )
            }
        }
    }
    Text(text = annotated, style = style, modifier = modifier)
}
