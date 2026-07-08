package to.sava.peranta.filter

import to.sava.peranta.model.Priority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilterRuleCodecTest {

    /** ルール一覧は JSON を往復して同じ内容に戻る。 */
    @Test
    fun rulesRoundTripThroughJson() {
        val rules = listOf(
            FilterRule("com.example.a", RuleAction.EXCLUDE),
            FilterRule("com.example.b", RuleAction.INCLUDE, priorityOverride = Priority.HIGH, redact = true),
        )
        assertEquals(rules, decodeFilterRules(encodeFilterRules(rules)))
    }

    /** null・空・壊れた JSON は空リストへフォールバックする。 */
    @Test
    fun malformedInputFallsBackToEmpty() {
        assertTrue(decodeFilterRules(null).isEmpty())
        assertTrue(decodeFilterRules("").isEmpty())
        assertTrue(decodeFilterRules("not json").isEmpty())
    }
}
