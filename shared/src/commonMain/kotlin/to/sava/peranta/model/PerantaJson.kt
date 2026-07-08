package to.sava.peranta.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** プロジェクト共通の JSON 設定。type を判別フィールドに使い、未知フィールドは無視する。 */
val PerantaJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

fun encodePayload(payload: Payload): String = PerantaJson.encodeToString(payload)

fun decodePayload(json: String): Payload = PerantaJson.decodeFromString(json)

fun encodeEnvelope(envelope: Envelope): String = PerantaJson.encodeToString(envelope)

fun decodeEnvelope(json: String): Envelope = PerantaJson.decodeFromString(json)
