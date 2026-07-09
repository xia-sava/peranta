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
 * [packageName] を転送対象から除外するようフィルタルール一覧を更新する（muteApp コマンド、§7）。
 * 既存ルールがあれば action を EXCLUDE へ差し替え、無ければ EXCLUDE ルールを追加する。
 * 既に除外済みなら変更せず同じインスタンスを返す（不要な保存を避けられるよう参照同一性で示す）。
 */
fun mutePackage(rules: List<FilterRule>, packageName: String): List<FilterRule> {
    val existing = rules.firstOrNull { it.packageName == packageName }
    return when {
        existing == null -> rules + FilterRule(packageName, RuleAction.EXCLUDE)
        existing.action == RuleAction.EXCLUDE -> rules
        else -> rules.map {
            if (it.packageName == packageName) it.copy(action = RuleAction.EXCLUDE) else it
        }
    }
}

/**
 * [packageName] の除外（EXCLUDE）ルールを取り除いて転送対象へ戻す（unmuteApp コマンド、§7）。
 * 除外ルールが無ければ変更せず同じインスタンスを返す（不要な保存を避けられるよう参照同一性で示す）。
 * INCLUDE ルール（システム暗黙除外からの復帰指定）には触れない。
 */
fun unmutePackage(rules: List<FilterRule>, packageName: String): List<FilterRule> {
    val existing = rules.firstOrNull { it.packageName == packageName }
    return if (existing != null && existing.action == RuleAction.EXCLUDE) {
        rules.filterNot { it === existing }
    } else {
        rules
    }
}

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

/**
 * [packageName] を denylist の暗黙除外対象（システムアプリ扱い）とみなすか判定する（§7）。
 * ランチャーアイコンを持つアプリ（[hasLauncherIcon] が true）は FLAG_SYSTEM でも通常アプリとして
 * 転送対象に含め、プリインの Gmail 等を誤って除外しない。ランチャーを持たないものだけ暗黙除外とし、
 * ランチャーの有無に依らず隠したい [systemPackages] のベースラインと OR で合成する。
 *
 * [isCrossProfilePackage] は、通知の発生元が自ユーザーと異なるプロファイル（work profile 等）の
 * ものであることを示す。この場合、個人プロファイルの PackageManager では対象パッケージのランチャー
 * 有無を判定できず [hasLauncherIcon] が偽になり得るため、ランチャー判定に基づく暗黙除外は行わない
 * （疑わしきは転送）。[systemPackages] によるパッケージ名一致の除外は、プロファイルを問わず適用する。
 */
fun isImplicitlySystemPackage(
    packageName: String,
    hasLauncherIcon: Boolean,
    isCrossProfilePackage: Boolean = false,
    systemPackages: Set<String> = DEFAULT_SYSTEM_PACKAGES,
): Boolean = packageName in systemPackages || (!hasLauncherIcon && !isCrossProfilePackage)
