package to.sava.peranta.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class LinkifiedTextTest {

    private fun matches(text: String): List<String> = findUrlRanges(text).map { text.substring(it.first, it.last + 1) }

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
}
