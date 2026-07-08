package to.sava.peranta.filter

import to.sava.peranta.model.Priority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** muteApp コマンドがフィルタルールへ与える変更を検証する（§7）。 */
class MutePackageTest {

    /** ルールが無いパッケージには EXCLUDE ルールが追加される。 */
    @Test
    fun addsExcludeRuleWhenAbsent() {
        val result = mutePackage(emptyList(), "com.spam")
        assertEquals(
            listOf(FilterRule("com.spam", RuleAction.EXCLUDE)),
            result,
        )
    }

    /** 既に INCLUDE ルールがあるパッケージは、他の設定を保ったまま EXCLUDE へ差し替わる。 */
    @Test
    fun flipsIncludeRuleToExclude() {
        val rules = listOf(
            FilterRule("com.keep", RuleAction.INCLUDE),
            FilterRule("com.spam", RuleAction.INCLUDE, priorityOverride = Priority.HIGH, redact = true),
        )
        val result = mutePackage(rules, "com.spam")
        assertEquals(FilterRule("com.keep", RuleAction.INCLUDE), result[0])
        assertEquals(
            FilterRule("com.spam", RuleAction.EXCLUDE, priorityOverride = Priority.HIGH, redact = true),
            result[1],
        )
    }

    /** 既に EXCLUDE 済みなら変更せず同じインスタンスを返す（不要な保存を避ける）。 */
    @Test
    fun keepsSameInstanceWhenAlreadyExcluded() {
        val rules = listOf(FilterRule("com.spam", RuleAction.EXCLUDE))
        assertSame(rules, mutePackage(rules, "com.spam"))
    }

    /** 他パッケージのルールには触れない。 */
    @Test
    fun leavesOtherPackagesUntouched() {
        val rules = listOf(FilterRule("com.other", RuleAction.EXCLUDE))
        val result = mutePackage(rules, "com.spam")
        assertTrue(result.contains(FilterRule("com.other", RuleAction.EXCLUDE)))
        assertTrue(result.contains(FilterRule("com.spam", RuleAction.EXCLUDE)))
    }
}
