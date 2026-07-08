package to.sava.peranta.model

import kotlinx.serialization.Serializable

/** Envelope の現行バージョン。 */
const val ENVELOPE_VERSION: Int = 1

/** 暗号化された Payload を鍵情報とともに運ぶ封筒。nonce と ciphertext は base64。 */
@Serializable
data class Envelope(
    val v: Int = ENVELOPE_VERSION,
    val keyId: String,
    val nonce: String,
    val ciphertext: String,
)
