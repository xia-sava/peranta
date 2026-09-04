package to.sava.peranta.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
 * コードとして拾う連続半角数字の最小桁数。これ未満の数字列（金額の桁区切り・日付・時刻など）は
 * リンク化せず、本文の見た目を保つ。
 */
private const val MIN_CODE_DIGITS: Int = 6

/** コードを検出する正規表現。半角数字が [MIN_CODE_DIGITS] 桁以上続く範囲を候補とする。 */
private val CODE_PATTERN: Regex = Regex("[0-9]{$MIN_CODE_DIGITS,}")

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
 * [text] 中の最初の URL を返す（無ければ null）。トースト・ミラー通知の「開く」導線（§3.3）で、
 * 本文から開ける先を 1 個だけ選ぶために使う。
 */
internal fun firstUrl(text: String): String? =
    findUrlRanges(text).firstOrNull()?.let { text.substring(it.first, it.last + 1) }

/**
 * [text] に含まれるコードの範囲一覧を検出する（純関数）。半角数字が [MIN_CODE_DIGITS] 桁以上続く
 * 範囲を候補とし、URL に重なるもの（[findUrlRanges]）は除く。URL は開く先であってコピーする値では
 * ないため、URL 中の数字はコードとして扱わない。
 */
internal fun findCodeRanges(text: String): List<IntRange> {
    val urls = findUrlRanges(text)
    return CODE_PATTERN.findAll(text)
        .map { it.range }
        .filter { code -> urls.none { url -> code.first <= url.last && url.first <= code.last } }
        .toList()
}

/**
 * 本文中の URL をリンク化して表示する（タイムラインの通常テキスト・通知/SMS 本文・ファイルの
 * キャプションで共通に使う）。[findUrlRanges] で見つけた URL 部分に下線付きの
 * [MaterialTheme.colorScheme.primary] を当て、[LinkAnnotation.Url]（Compose 標準機構）が
 * [androidx.compose.ui.platform.LocalUriHandler] 経由でタップ/クリックを既定ブラウザ起動へ自動で流す。
 * リンクのタップ領域は URL 文字列に限られるため、バブル全体のスワイプ等の既存ジェスチャとは干渉しない。
 *
 * [onCopyCode] を渡すと [findCodeRanges] が見つけたコードも同じ見た目のリンクにし、押すと
 * その文字列を渡す（§10.1）。null のときはコードを検出しない。
 */
@Composable
internal fun LinkifiedText(
    text: String,
    style: TextStyle = LocalTextStyle.current,
    modifier: Modifier = Modifier,
    onCopyCode: ((code: String) -> Unit)? = null,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    // 押した時点の処理を呼ぶ。ラムダの同一性で注釈を作り直さないよう、キーには有無だけを使う。
    val currentOnCopyCode by rememberUpdatedState(onCopyCode)
    val codesEnabled = onCopyCode != null
    val annotated = remember(text, linkColor, codesEnabled) {
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
            if (codesEnabled) {
                findCodeRanges(text).forEach { range ->
                    val code = text.substring(range.first, range.last + 1)
                    addLink(
                        clickable = LinkAnnotation.Clickable(
                            tag = code,
                            styles = linkStyles,
                            linkInteractionListener = { currentOnCopyCode?.invoke(code) },
                        ),
                        start = range.first,
                        end = range.last + 1,
                    )
                }
            }
        }
    }
    Text(text = annotated, style = style, modifier = modifier)
}
