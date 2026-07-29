package to.sava.peranta.update

import co.touchlab.kermit.Logger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** マニフェストの署名方式（§12）。minSdk 30 の Android と JVM の双方が標準で持つ。 */
private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
private const val KEY_ALGORITHM = "EC"

private val log = Logger.withTag("ManifestSignature")

actual fun verifyManifestSignature(manifest: ByteArray, signature: String, publicKey: String): Boolean =
    try {
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initVerify(publicKeyOf(publicKey))
            update(manifest)
            verify(decodeBase64(signature))
        }
    } catch (error: GeneralSecurityException) {
        log.w(error) { "manifest signature verification failed" }
        false
    } catch (error: IllegalArgumentException) {
        log.w(error) { "manifest signature is not base64" }
        false
    }

private fun publicKeyOf(base64: String): PublicKey =
    KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(decodeBase64(base64)))

/** 前後の空白を落としてから復号する。配信の都合で末尾に改行が付くことがある。 */
private fun decodeBase64(text: String): ByteArray = Base64.getDecoder().decode(text.trim())
