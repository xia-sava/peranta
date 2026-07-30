package to.sava.peranta.filter

import to.sava.peranta.model.AppRuleSettings
import to.sava.peranta.model.Priority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * アプリフィルタ画面（§10.4）のチェック状態⇄ルール変換ロジックを検証する。
 * チェックの意味がモードで反転すること・システム暗黙除外の扱い・詳細設定の保持を中心に見る。
 */
class AppFilterTest {

    // --- denylist のチェック状態（チェック＝除外） ---

    /** denylist で通常アプリはルールが無ければ転送されるためチェックは外れている。 */
    @Test
    fun denylistNormalAppUncheckedByDefault() {
        assertFalse(isPackageChecked(emptyList(), "com.app", FilterMode.DENYLIST, isSystemPackage = false))
    }

    /** denylist で EXCLUDE ルールを持つ通常アプリはチェックが入る（除外＝転送しない）。 */
    @Test
    fun denylistExcludedAppChecked() {
        val rules = listOf(FilterRule("com.app", RuleAction.EXCLUDE))
        assertTrue(isPackageChecked(rules, "com.app", FilterMode.DENYLIST, isSystemPackage = false))
    }

    /** denylist でシステムアプリはルールが無くても暗黙除外されるためチェックが入る。 */
    @Test
    fun denylistSystemAppCheckedByDefault() {
        assertTrue(isPackageChecked(emptyList(), "com.sys", FilterMode.DENYLIST, isSystemPackage = true))
    }

    /** denylist でシステムアプリを INCLUDE で復帰させると転送されるためチェックが外れる。 */
    @Test
    fun denylistIncludedSystemAppUnchecked() {
        val rules = listOf(FilterRule("com.sys", RuleAction.INCLUDE))
        assertFalse(isPackageChecked(rules, "com.sys", FilterMode.DENYLIST, isSystemPackage = true))
    }

    // --- allowlist のチェック状態（チェック＝許可） ---

    /** allowlist ではルールが無ければ転送されないためチェックは外れている。 */
    @Test
    fun allowlistAppUncheckedByDefault() {
        assertFalse(isPackageChecked(emptyList(), "com.app", FilterMode.ALLOWLIST, isSystemPackage = false))
    }

    /** allowlist で INCLUDE ルールを持つアプリはチェックが入る（許可＝転送する）。 */
    @Test
    fun allowlistIncludedAppChecked() {
        val rules = listOf(FilterRule("com.app", RuleAction.INCLUDE))
        assertTrue(isPackageChecked(rules, "com.app", FilterMode.ALLOWLIST, isSystemPackage = false))
    }

    // --- チェック操作のルール書き込み ---

    /** denylist で通常アプリにチェックを入れると EXCLUDE ルールが加わる。 */
    @Test
    fun denylistCheckingNormalAppAddsExclude() {
        val result = setPackageChecked(emptyList(), "com.app", checked = true, FilterMode.DENYLIST, isSystemPackage = false)
        assertEquals(listOf(FilterRule("com.app", RuleAction.EXCLUDE)), result)
    }

    /** denylist でシステムアプリのチェックを外すと INCLUDE ルール（暗黙除外からの復帰）が加わる。 */
    @Test
    fun denylistUncheckingSystemAppAddsInclude() {
        val result = setPackageChecked(emptyList(), "com.sys", checked = false, FilterMode.DENYLIST, isSystemPackage = true)
        assertEquals(listOf(FilterRule("com.sys", RuleAction.INCLUDE)), result)
    }

    /** allowlist でチェックを入れると INCLUDE ルールが加わる。 */
    @Test
    fun allowlistCheckingAddsInclude() {
        val result = setPackageChecked(emptyList(), "com.app", checked = true, FilterMode.ALLOWLIST, isSystemPackage = false)
        assertEquals(listOf(FilterRule("com.app", RuleAction.INCLUDE)), result)
    }

    /** allowlist でチェックを外すと INCLUDE ルールが取り除かれる。 */
    @Test
    fun allowlistUncheckingRemovesInclude() {
        val rules = listOf(FilterRule("com.app", RuleAction.INCLUDE))
        assertTrue(setPackageChecked(rules, "com.app", checked = false, FilterMode.ALLOWLIST, isSystemPackage = false).isEmpty())
    }

    /** チェック状態が既に目標どおりなら同じインスタンスを返す（不要な保存を避ける）。 */
    @Test
    fun checkingWhenAlreadyCheckedKeepsInstance() {
        val rules = listOf(FilterRule("com.app", RuleAction.EXCLUDE))
        assertSame(rules, setPackageChecked(rules, "com.app", checked = true, FilterMode.DENYLIST, isSystemPackage = false))
    }

    // --- 詳細設定（優先度上書き・伏せ字）の保持 ---

    /** 通常アプリに優先度上書きを付けると、転送を保つため INCLUDE ルールに載る。 */
    @Test
    fun detailPriorityOnForwardedAppUsesInclude() {
        val result = updatePackageDetail(
            emptyList(), "com.app", priorityOverride = Priority.HIGH, redact = false,
            swipeDismissesSource = false, mode = FilterMode.DENYLIST, isSystemPackage = false,
        )
        assertEquals(listOf(FilterRule("com.app", RuleAction.INCLUDE, priorityOverride = Priority.HIGH)), result)
    }

    /** 詳細設定を持つ除外アプリをチェック解除（unmute）すると、設定を保ったまま INCLUDE へ戻る。 */
    @Test
    fun detailThenUnmuteKeepsOverridesAsInclude() {
        val muted = setPackageChecked(
            updatePackageDetail(
                emptyList(), "com.app", Priority.HIGH, redact = true,
                swipeDismissesSource = false, mode = FilterMode.DENYLIST, isSystemPackage = false,
            ),
            "com.app", checked = true, FilterMode.DENYLIST, isSystemPackage = false,
        )
        assertEquals(FilterRule("com.app", RuleAction.EXCLUDE, Priority.HIGH, redact = true), muted.single())
        val unmuted = setPackageChecked(muted, "com.app", checked = false, FilterMode.DENYLIST, isSystemPackage = false)
        assertEquals(FilterRule("com.app", RuleAction.INCLUDE, Priority.HIGH, redact = true), unmuted.single())
    }

    /** 詳細設定を空にすると、既定の転送に一致する通常アプリはルール自体が消える。 */
    @Test
    fun clearingDetailDropsRedundantRule() {
        val rules = listOf(FilterRule("com.app", RuleAction.INCLUDE, priorityOverride = Priority.HIGH))
        val result = updatePackageDetail(
            rules, "com.app", priorityOverride = null, redact = false,
            swipeDismissesSource = false, mode = FilterMode.DENYLIST, isSystemPackage = false,
        )
        assertTrue(result.isEmpty())
    }

    // --- ルールをまとめて置き換える（受信端末からの設定変更、§3.4 / §7） ---

    /** applyAppRule は転送可否と詳細をまとめて反映する。 */
    @Test
    fun applyAppRuleSetsForwardAndDetailTogether() {
        val settings = AppRuleSettings(
            forward = false,
            priorityOverride = Priority.HIGH,
            redact = true,
            swipeDismissesSource = true,
        )
        val result = applyAppRule(emptyList(), "com.app", settings, FilterMode.DENYLIST, isSystemPackage = false)
        assertEquals(
            FilterRule("com.app", RuleAction.EXCLUDE, Priority.HIGH, redact = true, swipeDismissesSource = true),
            result.single(),
        )
    }

    /** 払いのけの扱いだけを持つルールは、転送が既定のままでも残る（設定が消えない）。 */
    @Test
    fun applyAppRuleKeepsRuleWithOnlySwipeSetting() {
        val settings = AppRuleSettings(forward = true, swipeDismissesSource = true)
        val result = applyAppRule(emptyList(), "com.app", settings, FilterMode.DENYLIST, isSystemPackage = false)
        assertEquals(FilterRule("com.app", RuleAction.INCLUDE, swipeDismissesSource = true), result.single())
    }

    /** 扱いを全て既定に戻すと、不要になったルールは残らない。 */
    @Test
    fun applyAppRuleDropsRuleWhenEverythingIsDefault() {
        val rules = listOf(FilterRule("com.app", RuleAction.EXCLUDE, redact = true))
        val settings = AppRuleSettings(forward = true)
        val result = applyAppRule(rules, "com.app", settings, FilterMode.DENYLIST, isSystemPackage = false)
        assertTrue(result.isEmpty())
    }

    /** appRuleSettingsFor は現在の扱いをそのまま読み出す。 */
    @Test
    fun appRuleSettingsForReadsCurrentRule() {
        val rules = listOf(FilterRule("com.app", RuleAction.EXCLUDE, Priority.LOW, redact = true))
        val settings = appRuleSettingsFor(rules, "com.app", FilterMode.DENYLIST, isSystemPackage = false)
        assertEquals(AppRuleSettings(forward = false, priorityOverride = Priority.LOW, redact = true), settings)
    }

    // --- グループ（システムアプリ折りたたみ）の TriState ---

    private val systemPackages = setOf("com.a", "com.b", "com.c")
    private fun isSystem(packageName: String): Boolean = packageName in systemPackages

    /** システムアプリは既定で全除外のため、グループは全チェック。 */
    @Test
    fun systemGroupAllCheckedByDefault() {
        val state = groupCheckState(systemPackages.toList(), emptyList(), FilterMode.DENYLIST, ::isSystem)
        assertEquals(GroupCheckState.ALL_CHECKED, state)
    }

    /** 一部を INCLUDE で復帰させるとグループは中間状態になる。 */
    @Test
    fun systemGroupPartialWhenSomeIncluded() {
        val rules = listOf(FilterRule("com.b", RuleAction.INCLUDE))
        val state = groupCheckState(systemPackages.toList(), rules, FilterMode.DENYLIST, ::isSystem)
        assertEquals(GroupCheckState.PARTIALLY_CHECKED, state)
    }

    /** グループ一括でチェックを外すと全メンバに INCLUDE ルールが付く。 */
    @Test
    fun setGroupUncheckedIncludesAll() {
        val result = setGroupChecked(emptyList(), systemPackages.toList(), checked = false, FilterMode.DENYLIST, ::isSystem)
        assertEquals(systemPackages, result.filter { it.action == RuleAction.INCLUDE }.map { it.packageName }.toSet())
    }

    /** 空グループはチェックなし扱い。 */
    @Test
    fun emptyGroupIsNoneChecked() {
        assertEquals(GroupCheckState.NONE_CHECKED, groupCheckState(emptyList(), emptyList(), FilterMode.DENYLIST, ::isSystem))
    }
}
