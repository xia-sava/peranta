package to.sava.peranta.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class LinkifiedTextTest {

    private fun matches(text: String): List<String> = findUrlRanges(text).map { text.substring(it.first, it.last + 1) }

    private fun codes(text: String): List<String> = findCodeRanges(text).map { text.substring(it.first, it.last + 1) }

    /** URL を含まない本文では検出範囲が空。 */
    @Test
    fun noUrlReturnsEmptyRanges() {
        assertEquals(emptyList(), findUrlRanges("ただのテキストです"))
    }

    /** 単一の https URL を検出する。 */
    @Test
    fun detectsSingleHttpsUrl() {
        assertEquals(listOf("https://example.com/path"), matches("見て https://example.com/path です"))
    }

    /** http（非 https）の URL も検出する。 */
    @Test
    fun detectsHttpUrl() {
        val text = "http://example.com"
        assertEquals(listOf(text), matches(text))
    }

    /** 複数の URL をすべて検出する。 */
    @Test
    fun detectsMultipleUrls() {
        assertEquals(
            listOf("https://a.example/", "https://b.example/"),
            matches("https://a.example/ と https://b.example/ を見て"),
        )
    }

    /** 末尾の閉じ括弧は URL に含めない。 */
    @Test
    fun trimsTrailingCloseParenthesis() {
        assertEquals(listOf("https://example.com/page"), matches("(https://example.com/page)"))
    }

    /** 末尾の句点・読点は URL に含めない。 */
    @Test
    fun trimsTrailingJapanesePunctuation() {
        assertEquals(listOf("https://example.com/info"), matches("詳細は https://example.com/info。"))
        assertEquals(listOf("https://example.com/info"), matches("詳細は https://example.com/info、"))
    }

    /** "ttp://" のようにスキームが不完全な文字列は URL とみなさない。 */
    @Test
    fun ignoresIncompleteScheme() {
        assertEquals(emptyList(), findUrlRanges("これは ttp://example.com ではない"))
    }

    /** 6 桁続く半角数字をコードとして検出する。 */
    @Test
    fun detectsSixDigitCode() {
        assertEquals(listOf("483920"), codes("認証コードは 483920 です"))
    }

    /** 5 桁以下の数字はコードとみなさない。金額の桁区切り・日付・時刻を拾わない。 */
    @Test
    fun ignoresShortDigitRuns() {
        assertEquals(emptyList(), findCodeRanges("12,345円を9/30の12:34までに"))
    }

    /** 6 桁を超える数字列も途中で切らず、続く限りを 1 つのコードとして扱う。 */
    @Test
    fun detectsLongerDigitRun() {
        assertEquals(listOf("09012345678"), codes("連絡先は 09012345678 です"))
    }

    /** 本文にコードが複数あればすべて検出する。 */
    @Test
    fun detectsMultipleCodes() {
        assertEquals(listOf("123456", "654321"), codes("旧 123456 新 654321"))
    }

    /** 全角数字はコードとみなさない。 */
    @Test
    fun ignoresFullWidthDigits() {
        assertEquals(emptyList(), findCodeRanges("１２３４５６"))
    }

    /** URL に含まれる数字はコードとして扱わない。URL は開く先であってコピーする値ではない。 */
    @Test
    fun ignoresDigitsInsideUrl() {
        assertEquals(emptyList(), findCodeRanges("https://example.com/123456"))
    }

    /** URL と本文のコードが並ぶ本文では、URL の外にあるものだけを検出する。 */
    @Test
    fun detectsCodeOutsideUrl() {
        assertEquals(listOf("483920"), codes("https://example.com/999999 のコードは 483920"))
    }
}
