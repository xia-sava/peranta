package to.sava.peranta.filter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import to.sava.peranta.model.Priority

/** フィルタの動作モード（§7）。 */
@Serializable
enum class FilterMode {
    /** 既定。基本すべて転送し、除外ルールと暗黙のシステム除外だけを落とす。 */
    @SerialName("denylist")
    DENYLIST,

    /** 許可ルールに載ったパッケージだけを転送する。 */
    @SerialName("allowlist")
    ALLOWLIST,
}

/** ルールがそのパッケージを転送対象に含めるか除くか。 */
@Serializable
enum class RuleAction {
    /** 転送しない（denylist の除外指定）。 */
    @SerialName("exclude")
    EXCLUDE,

    /** 転送する（allowlist の許可指定、または denylist の暗黙除外からの復帰）。 */
    @SerialName("include")
    INCLUDE,
}

/**
 * パッケージ単位のフィルタルール（§7）。
 * [action] でモードに応じた包含/除外を、[priorityOverride] で優先度上書きを、
 * [redact] でタイトル・本文の伏せ字を指定する。
 */
@Serializable
data class FilterRule(
    val packageName: String,
    val action: RuleAction,
    val priorityOverride: Priority? = null,
    val redact: Boolean = false,
)

/**
 * 暗黙に除外するシステム系パッケージ（§7）。
 * denylist モードでルールが無い場合、これらは転送しない。個別 INCLUDE ルールで復帰できる。
 */
val DEFAULT_SYSTEM_PACKAGES: Set<String> = setOf(
    "android",
    "com.android.systemui",
    "com.android.settings",
    "com.android.shell",
    "com.android.providers.downloads",
    "com.google.android.gms",
    "com.google.android.gsf",
)
