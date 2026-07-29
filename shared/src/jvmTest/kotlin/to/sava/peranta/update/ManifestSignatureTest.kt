package to.sava.peranta.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManifestSignatureTest {

    private val key = TestSigningKey()
    private val manifest = """{ "desktop": { "versionCode": 20, "versionName": "2.0.0", "sha256": "d2" } }"""

    /** 対応する鍵で作った署名は、そのバイト列に対して通る。 */
    @Test
    fun acceptsSignatureMadeByMatchingKey() {
        val signature = key.sign(manifest)

        assertTrue(verifyManifestSignature(manifest.encodeToByteArray(), signature, key.publicKey))
    }

    /** 署名の対象と 1 バイトでも違うバイト列は通らない。 */
    @Test
    fun rejectsTamperedManifest() {
        val signature = key.sign(manifest)
        val tampered = manifest.replace("\"versionCode\": 20", "\"versionCode\": 21")

        assertFalse(verifyManifestSignature(tampered.encodeToByteArray(), signature, key.publicKey))
    }

    /** 別の鍵で作った署名は通らない。 */
    @Test
    fun rejectsSignatureMadeByAnotherKey() {
        val signature = TestSigningKey().sign(manifest)

        assertFalse(verifyManifestSignature(manifest.encodeToByteArray(), signature, key.publicKey))
    }

    /** 空の署名は通らない。 */
    @Test
    fun rejectsEmptySignature() {
        assertFalse(verifyManifestSignature(manifest.encodeToByteArray(), "", key.publicKey))
    }

    /** base64 として読めない署名は通らない。 */
    @Test
    fun rejectsUnreadableSignature() {
        assertFalse(verifyManifestSignature(manifest.encodeToByteArray(), "not base64!!", key.publicKey))
    }

    /** 配信の都合で署名の末尾に改行が付いても通る。 */
    @Test
    fun acceptsSignatureWithSurroundingWhitespace() {
        val signature = key.sign(manifest)

        assertTrue(verifyManifestSignature(manifest.encodeToByteArray(), "$signature\n", key.publicKey))
    }

    /** 埋め込みの公開鍵は、テストで作った鍵の署名を受け付けない。 */
    @Test
    fun embeddedPublicKeyRejectsForeignSignature() {
        val signature = key.sign(manifest)

        assertFalse(verifyManifestSignature(manifest.encodeToByteArray(), signature, MANIFEST_PUBLIC_KEY))
    }
}
