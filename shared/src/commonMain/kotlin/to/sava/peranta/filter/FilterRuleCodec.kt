package to.sava.peranta.filter

import co.touchlab.kermit.Logger
import kotlinx.serialization.builtins.ListSerializer
import to.sava.peranta.model.PerantaJson

/** フィルタルール符号化のログ出力先。本文は載せず失敗種別のみを残す。 */
private val log = Logger.withTag("FilterRuleCodec")

/** フィルタルールの一覧を JSON 文字列へ符号化する（設定保存用）。 */
fun encodeFilterRules(rules: List<FilterRule>): String =
    PerantaJson.encodeToString(ListSerializer(FilterRule.serializer()), rules)

/** JSON 文字列からフィルタルール一覧を復号する。解析に失敗したら warn ログ付きで空リストを返す。 */
fun decodeFilterRules(json: String?): List<FilterRule> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        PerantaJson.decodeFromString(ListSerializer(FilterRule.serializer()), json)
    }.onFailure { error ->
        log.w { "failed to decode filter rules (${error::class.simpleName})" }
    }.getOrDefault(emptyList())
}
