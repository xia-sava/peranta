package to.sava.peranta.filter

import to.sava.peranta.model.Priority

/**
 * アプリフィルタ画面（§10.4）のチェックボックス集合が取り得る状態。
 * システムアプリ折りたたみグループの [androidx.compose.ui.state.ToggleableState] へ写すために、
 * UI 非依存の純粋な値として持つ。
 */
enum class GroupCheckState {
    /** 全メンバがチェック済み。 */
    ALL_CHECKED,

    /** チェック済みメンバなし。 */
    NONE_CHECKED,

    /** 一部だけチェック済み。 */
    PARTIALLY_CHECKED,
}

/**
 * ルールが無い（既定）ときにそのパッケージが転送されるか（§7）。
 * denylist は暗黙システム除外だけを落とすため、システムアプリ以外は既定で転送する。
 * allowlist は許可ルールに載ったものだけ転送するため、既定では転送しない。
 */
private fun defaultForwardWithoutRule(mode: FilterMode, isSystemPackage: Boolean): Boolean =
    when (mode) {
        FilterMode.DENYLIST -> !isSystemPackage
        FilterMode.ALLOWLIST -> false
    }

/** 優先度上書き・伏せ字のいずれかが設定されているか（mute↔unmute でルールを残す判定に使う）。 */
private val FilterRule.hasDetailOverride: Boolean
    get() = priorityOverride != null || redact

/**
 * [packageName] に対する目標アクション（null は「ルール不要」）を、既存ルールを保ったまま適用する。
 * 変化が無ければ入力と同じインスタンスを返し（[ConfigRepository] の保存省略に効く）、
 * アクションだけを差し替える場合は優先度上書き・伏せ字を保持する。
 */
private fun applyRuleAction(
    rules: List<FilterRule>,
    packageName: String,
    existing: FilterRule?,
    targetAction: RuleAction?,
): List<FilterRule> = when {
    targetAction == null -> if (existing == null) rules else rules.filterNot { it === existing }
    existing == null -> rules + FilterRule(packageName, targetAction)
    existing.action == targetAction -> rules
    else -> rules.map { if (it === existing) it.copy(action = targetAction) else it }
}

/**
 * [packageName] の転送可否が [forward] になるよう、最小限のルール変更を施す（§7）。
 * 既定（ルール無し）で目標どおりに転送され、かつ保持すべき詳細設定（優先度上書き・伏せ字）も
 * 無い場合はルール自体を持たない。そうでなければ、転送するなら INCLUDE・落とすなら EXCLUDE の
 * 明示ルールを置き、既存の詳細設定は引き継ぐ。判定は [decideFilter] と同じ規則に従う。
 */
fun setPackageForwarded(
    rules: List<FilterRule>,
    packageName: String,
    forward: Boolean,
    mode: FilterMode,
    isSystemPackage: Boolean,
): List<FilterRule> {
    val existing = rules.firstOrNull { it.packageName == packageName }
    val defaultForward = defaultForwardWithoutRule(mode, isSystemPackage)
    val hasOverride = existing?.hasDetailOverride ?: false
    val targetAction = when {
        forward == defaultForward && !hasOverride -> null
        forward -> RuleAction.INCLUDE
        else -> RuleAction.EXCLUDE
    }
    return applyRuleAction(rules, packageName, existing, targetAction)
}

/**
 * [packageName] が現在のルールで転送されるか（§7）。UI のチェック状態と実転送エンジンの判定が
 * 食い違わないよう、[decideFilter] の転送判定をそのまま用いる。
 */
fun isPackageForwarded(
    rules: List<FilterRule>,
    packageName: String,
    mode: FilterMode,
    isSystemPackage: Boolean,
): Boolean =
    decideFilter(
        packageName = packageName,
        basePriority = Priority.NORMAL,
        isOtp = false,
        mode = mode,
        rules = rules,
        isImplicitlySystemPackage = { isSystemPackage },
    ).forward

/**
 * アプリフィルタ画面のチェックボックス状態（§10.4）。
 * denylist ではチェック＝除外（転送しない）、allowlist ではチェック＝許可（転送する）と意味が反転する。
 */
fun isPackageChecked(
    rules: List<FilterRule>,
    packageName: String,
    mode: FilterMode,
    isSystemPackage: Boolean,
): Boolean {
    val forwarded = isPackageForwarded(rules, packageName, mode, isSystemPackage)
    return when (mode) {
        FilterMode.DENYLIST -> !forwarded
        FilterMode.ALLOWLIST -> forwarded
    }
}

/**
 * チェックボックス操作をルール変更へ写す（§10.4）。チェックの意味はモードで反転するため、
 * まず目標の転送可否へ翻訳してから [setPackageForwarded] に委ねる。
 */
fun setPackageChecked(
    rules: List<FilterRule>,
    packageName: String,
    checked: Boolean,
    mode: FilterMode,
    isSystemPackage: Boolean,
): List<FilterRule> {
    val forward = when (mode) {
        FilterMode.DENYLIST -> !checked
        FilterMode.ALLOWLIST -> checked
    }
    return setPackageForwarded(rules, packageName, forward, mode, isSystemPackage)
}

/**
 * 詳細画面（§10.4）の優先度上書き・伏せ字設定をルールへ反映する。転送可否は変えず現状を保つ。
 * 上書きと伏せ字がどちらも無く、現状の転送可否が既定と一致するならルールを持たない（不要なルールを残さない）。
 * それ以外は転送するなら INCLUDE・落とすなら EXCLUDE の明示ルールへ設定値を載せる。
 */
fun updatePackageDetail(
    rules: List<FilterRule>,
    packageName: String,
    priorityOverride: Priority?,
    redact: Boolean,
    swipeDismissesSource: Boolean,
    mode: FilterMode,
    isSystemPackage: Boolean,
): List<FilterRule> {
    val existing = rules.firstOrNull { it.packageName == packageName }
    val forwarded = isPackageForwarded(rules, packageName, mode, isSystemPackage)
    val defaultForward = defaultForwardWithoutRule(mode, isSystemPackage)
    val hasOverride = priorityOverride != null || redact || swipeDismissesSource
    val targetAction = when {
        !hasOverride && forwarded == defaultForward -> null
        forwarded -> RuleAction.INCLUDE
        else -> RuleAction.EXCLUDE
    }
    if (targetAction == null) {
        return if (existing == null) rules else rules.filterNot { it === existing }
    }
    val updated = FilterRule(packageName, targetAction, priorityOverride, redact, swipeDismissesSource)
    return when {
        existing == null -> rules + updated
        existing == updated -> rules
        else -> rules.map { if (it === existing) updated else it }
    }
}

/** [packageNames] 全体のチェック状態を集約する（システムアプリ折りたたみグループの TriState 用、§10.4）。 */
fun groupCheckState(
    packageNames: List<String>,
    rules: List<FilterRule>,
    mode: FilterMode,
    isSystemPackage: (String) -> Boolean,
): GroupCheckState {
    if (packageNames.isEmpty()) return GroupCheckState.NONE_CHECKED
    val checkedCount = packageNames.count { isPackageChecked(rules, it, mode, isSystemPackage(it)) }
    return when (checkedCount) {
        0 -> GroupCheckState.NONE_CHECKED
        packageNames.size -> GroupCheckState.ALL_CHECKED
        else -> GroupCheckState.PARTIALLY_CHECKED
    }
}

/** [packageNames] をまとめて [checked] に設定する（グループの TriState 一括操作、§10.4）。 */
fun setGroupChecked(
    rules: List<FilterRule>,
    packageNames: List<String>,
    checked: Boolean,
    mode: FilterMode,
    isSystemPackage: (String) -> Boolean,
): List<FilterRule> =
    packageNames.fold(rules) { accumulated, packageName ->
        setPackageChecked(accumulated, packageName, checked, mode, isSystemPackage(packageName))
    }
