package to.sava.peranta.config

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256

/**
 * アクセストークンの指紋（SHA-256 の 16 進表記）を返す。未設定・空白のトークンには指紋を与えない。
 *
 * 「あとから同じトークンかどうかを言い当てる」ためだけの値で、トークンそのものは複製しない（§10.6、§11）。
 * 指紋からトークンは復元できないため秘密として保管する必要はないが、ログには出さない。
 */
fun accessTokenFingerprint(token: String?): String? =
    token?.takeIf { it.isNotBlank() }?.let { sha256Hex(it.encodeToByteArray()) }

private fun sha256Hex(bytes: ByteArray): String =
    CryptographyProvider.Default
        .get(SHA256)
        .hasher()
        .hashBlocking(bytes)
        .joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }
