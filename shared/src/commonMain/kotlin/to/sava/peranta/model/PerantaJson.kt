package to.sava.peranta.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * プロジェクト共通の JSON 設定。type を判別フィールドに使い、未知フィールドは無視する。
 * 復号に失敗したときの例外メッセージへ入力の JSON を載せない（本文やタイトルを含みうる例外が
 * ログへ流れても内容が残らないようにする。§11）。
 */
@OptIn(ExperimentalSerializationApi::class)
val PerantaJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    exceptionsWithDebugInfo = false
}

fun encodePayload(payload: Payload): String = PerantaJson.encodeToString(payload)

fun decodePayload(json: String): Payload = PerantaJson.decodeFromString(json)

fun encodeEnvelope(envelope: Envelope): String = PerantaJson.encodeToString(envelope)

fun decodeEnvelope(json: String): Envelope = PerantaJson.decodeFromString(json)
