package to.sava.peranta.filter

import to.sava.peranta.model.Priority

/**
 * フィルタ判定の結果。
 * [forward] が false なら転送しない。true のとき [priority] と [redact] を適用する。
 */
data class FilterDecision(
    val forward: Boolean,
    val priority: Priority,
    val redact: Boolean,
)

/**
 * 通知 1 件の転送可否・優先度・伏せ字を純粋に判定する（§7）。
 *
 * - denylist: ルールが無ければシステム暗黙除外を除いて転送。EXCLUDE ルールで除外、
 *   INCLUDE ルールで暗黙除外からの復帰。
 * - allowlist: INCLUDE ルールに載ったパッケージだけ転送。
 * - 優先度は [basePriority] を [FilterRule.priorityOverride] で上書きし、
 *   [isOtp] が true なら HIGH へ昇格する。
 *
 * denylist の暗黙除外は [isImplicitlySystemPackage] で判定する。既定は静的な [DEFAULT_SYSTEM_PACKAGES]
 * のみを見るが、送信側はランチャー有無を加味した動的判定（[isImplicitlySystemPackage] 純関数）を注入する。
 */
fun decideFilter(
    packageName: String,
    basePriority: Priority,
    isOtp: Boolean,
    mode: FilterMode,
    rules: List<FilterRule>,
    isImplicitlySystemPackage: (String) -> Boolean = { it in DEFAULT_SYSTEM_PACKAGES },
): FilterDecision {
    val rule = rules.firstOrNull { it.packageName == packageName }
    val forward = when (mode) {
        FilterMode.DENYLIST -> when (rule?.action) {
            RuleAction.EXCLUDE -> false
            RuleAction.INCLUDE -> true
            null -> !isImplicitlySystemPackage(packageName)
        }

        FilterMode.ALLOWLIST -> rule?.action == RuleAction.INCLUDE
    }

    val priority = (rule?.priorityOverride ?: basePriority).let { promoted ->
        if (isOtp) maxPriority(promoted, Priority.HIGH) else promoted
    }

    return FilterDecision(
        forward = forward,
        priority = priority,
        redact = rule?.redact ?: false,
    )
}

/** 2 つの優先度のうち高い方を返す（LOW < NORMAL < HIGH）。 */
private fun maxPriority(a: Priority, b: Priority): Priority =
    if (a.ordinal >= b.ordinal) a else b
