package to.sava.peranta.filter

import to.sava.peranta.model.Priority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** unmuteApp コマンドがフィルタルールへ与える変更を検証する（§7）。 */
class UnmutePackageTest {

    /** 除外ルールを持つパッケージは、そのルールが取り除かれ転送対象へ戻る。 */
    @Test
    fun removesExcludeRule() {
        val rules = listOf(
            FilterRule("com.keep", RuleAction.EXCLUDE),
            FilterRule("com.spam", RuleAction.EXCLUDE),
        )
        val result = unmutePackage(rules, "com.spam")
        assertEquals(listOf(FilterRule("com.keep", RuleAction.EXCLUDE)), result)
    }

    /** 除外ルールを持たないパッケージは変更せず同じインスタンスを返す（不要な保存を避ける）。 */
    @Test
    fun keepsSameInstanceWhenNoExcludeRule() {
        val rules = listOf(FilterRule("com.other", RuleAction.EXCLUDE))
        assertSame(rules, unmutePackage(rules, "com.spam"))
    }

    /** INCLUDE ルール（システム暗黙除外からの復帰指定）には触れない。 */
    @Test
    fun leavesIncludeRuleUntouched() {
        val rules = listOf(FilterRule("com.spam", RuleAction.INCLUDE, priorityOverride = Priority.HIGH))
        assertSame(rules, unmutePackage(rules, "com.spam"))
    }

    /** 空のルール一覧はそのまま返る。 */
    @Test
    fun emptyRulesStayEmpty() {
        assertTrue(unmutePackage(emptyList(), "com.spam").isEmpty())
    }
}
