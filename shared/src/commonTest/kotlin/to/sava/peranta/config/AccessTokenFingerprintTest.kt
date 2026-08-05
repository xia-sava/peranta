package to.sava.peranta.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class AccessTokenFingerprintTest {

    /** 既知の入力に対して SHA-256 を 16 進小文字で返す。 */
    @Test
    fun fingerprintIsSha256Hex() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            accessTokenFingerprint("abc"),
        )
    }

    /** 同じトークンは同じ指紋、違うトークンは違う指紋になる（同一性の判定に使えること）。 */
    @Test
    fun fingerprintIdentifiesTheToken() {
        assertEquals(accessTokenFingerprint("tk_same"), accessTokenFingerprint("tk_same"))
        assertNotEquals(accessTokenFingerprint("tk_old"), accessTokenFingerprint("tk_new"))
    }

    /** 指紋はトークンそのものを含まない（保存しても平文の複製にならない）。 */
    @Test
    fun fingerprintDoesNotContainTheToken() {
        val token = "tk_secret_value"
        assertEquals(false, accessTokenFingerprint(token)!!.contains(token))
    }

    /** 未設定・空白のトークンには指紋を与えない。 */
    @Test
    fun blankTokenHasNoFingerprint() {
        assertNull(accessTokenFingerprint(null))
        assertNull(accessTokenFingerprint(""))
        assertNull(accessTokenFingerprint("   "))
    }
}
