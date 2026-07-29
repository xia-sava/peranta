package to.sava.peranta.update

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * テスト用の ECDSA P-256 鍵ペア。埋め込みの公開鍵に対応する秘密鍵はテストからは使えないため、
 * 署名が通る経路を確かめるときはその場で作った鍵ペアの [publicKey] を検証側へ渡す。
 */
class TestSigningKey {

    private val keyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    /** X.509 SubjectPublicKeyInfo の DER を base64 にした公開鍵。 */
    val publicKey: String = Base64.getEncoder().encodeToString(keyPair.public.encoded)

    /** [content] のバイト列への署名を base64 で返す。 */
    fun sign(content: String): String =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(content.encodeToByteArray())
            Base64.getEncoder().encodeToString(sign())
        }
}

/** マニフェストと、その隣に置く署名を返す MockEngine。[signature] が null なら署名は 404 にする。 */
fun signedManifestEngine(manifestJson: String, signature: String?): MockEngine = MockEngine { request ->
    if (request.url.toString().endsWith(".sig")) {
        signature
            ?.let { body -> respond(content = body, status = HttpStatusCode.OK) }
            ?: respond(content = "", status = HttpStatusCode.NotFound)
    } else {
        respond(
            content = manifestJson,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }
}
