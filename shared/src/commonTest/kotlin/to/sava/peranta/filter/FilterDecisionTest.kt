package to.sava.peranta.filter

import to.sava.peranta.model.Priority
import to.sava.peranta.model.SwipeBehavior
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilterDecisionTest {

    private val systemPkg = "com.android.systemui"
    private val normalPkg = "com.example.bank"

    /** denylist: ルールが無ければ通常アプリは転送し、システム系は暗黙除外する。 */
    @Test
    fun denylistForwardsByDefaultButExcludesSystem() {
        assertTrue(decide(normalPkg).forward)
        assertFalse(decide(systemPkg).forward)
    }

    /** denylist: EXCLUDE ルールで通常アプリを除外できる。 */
    @Test
    fun denylistExcludeRuleDropsPackage() {
        val rules = listOf(FilterRule(normalPkg, RuleAction.EXCLUDE))
        assertFalse(decide(normalPkg, rules = rules).forward)
    }

    /** denylist: INCLUDE ルールでシステム暗黙除外から復帰できる。 */
    @Test
    fun denylistIncludeRuleRestoresSystemPackage() {
        val rules = listOf(FilterRule(systemPkg, RuleAction.INCLUDE))
        assertTrue(decide(systemPkg, rules = rules).forward)
    }

    /** allowlist: INCLUDE ルールに載ったパッケージだけ転送する。 */
    @Test
    fun allowlistForwardsOnlyIncluded() {
        val rules = listOf(FilterRule(normalPkg, RuleAction.INCLUDE))
        assertTrue(decide(normalPkg, FilterMode.ALLOWLIST, rules).forward)
        assertFalse(decide("com.example.other", FilterMode.ALLOWLIST, rules).forward)
    }

    /** 優先度上書きルールが基準優先度を置き換える。 */
    @Test
    fun priorityOverrideApplies() {
        val rules = listOf(FilterRule(normalPkg, RuleAction.INCLUDE, priorityOverride = Priority.LOW))
        assertEquals(Priority.LOW, decide(normalPkg, FilterMode.ALLOWLIST, rules).priority)
    }

    /** OTP 検出時は上書きより優先して HIGH へ昇格する。 */
    @Test
    fun otpPromotesToHigh() {
        val rules = listOf(FilterRule(normalPkg, RuleAction.INCLUDE, priorityOverride = Priority.LOW))
        val decision = decideFilter(normalPkg, Priority.NORMAL, isOtp = true, FilterMode.ALLOWLIST, rules)
        assertEquals(Priority.HIGH, decision.priority)
    }

    /** 注入した暗黙システム判定で、既定リスト外のパッケージも denylist の暗黙除外にできる。 */
    @Test
    fun injectedSystemPredicateExcludesPackage() {
        val decision = decideFilter(
            normalPkg,
            Priority.NORMAL,
            isOtp = false,
            FilterMode.DENYLIST,
            rules = emptyList(),
            isImplicitlySystemPackage = { it == normalPkg },
        )
        assertFalse(decision.forward)
    }

    /** 注入した判定が false を返すパッケージは、既定リストのシステム系でも転送する。 */
    @Test
    fun injectedSystemPredicateForwardsWhenNotSystem() {
        val decision = decideFilter(
            systemPkg,
            Priority.NORMAL,
            isOtp = false,
            FilterMode.DENYLIST,
            rules = emptyList(),
            isImplicitlySystemPackage = { false },
        )
        assertTrue(decision.forward)
    }

    /** redaction フラグが判定結果に反映される。 */
    @Test
    fun redactFlagPropagates() {
        val rules = listOf(FilterRule(normalPkg, RuleAction.INCLUDE, redact = true))
        assertTrue(decide(normalPkg, FilterMode.ALLOWLIST, rules).redact)
        assertFalse(decide(normalPkg).redact)
    }

    /** 払いのけの扱いは、既定では受信端末の表示を引っ込めるだけの指示になる。 */
    @Test
    fun swipeBehaviorDefaultsToLocalOnly() {
        assertEquals(SwipeBehavior.LOCAL_ONLY, decide(normalPkg).swipeBehavior)
    }

    /** 設定したアプリの通知には、払いのけで元通知も消す指示を載せる。 */
    @Test
    fun swipeDismissesSourceRulePropagates() {
        val rules = listOf(FilterRule(normalPkg, RuleAction.INCLUDE, swipeDismissesSource = true))
        assertEquals(SwipeBehavior.DISMISS_SOURCE, decide(normalPkg, FilterMode.ALLOWLIST, rules).swipeBehavior)
    }

    private fun decide(
        packageName: String,
        mode: FilterMode = FilterMode.DENYLIST,
        rules: List<FilterRule> = emptyList(),
    ): FilterDecision =
        decideFilter(packageName, Priority.NORMAL, isOtp = false, mode, rules)
}
