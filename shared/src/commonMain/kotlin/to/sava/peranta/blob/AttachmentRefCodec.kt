package to.sava.peranta.blob

import kotlinx.serialization.encodeToString
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.PerantaJson

/**
 * [AttachmentRef] を JSON 文字列へ直列化する（§4.3）。
 * Android のダウンロードはフォアグラウンドサービスで行うため、対象添付をサービス起動 Intent の
 * extras（文字列）で受け渡す。その封入・取り出しに使う。
 */
fun encodeAttachmentRef(ref: AttachmentRef): String = PerantaJson.encodeToString(ref)

/** [encodeAttachmentRef] で直列化した文字列を [AttachmentRef] へ復元する。 */
fun decodeAttachmentRef(json: String): AttachmentRef = PerantaJson.decodeFromString(json)
