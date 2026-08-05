package to.sava.peranta.ui.setup

import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.config.accessTokenFingerprint
import kotlin.test.Test
import kotlin.test.assertEquals

class NtfyCredentialProofTest {

    private fun config(token: String?, passedToken: String?): PerantaConfig =
        PerantaConfig(
            accessToken = token,
            selfTestPassedTokenFingerprint = accessTokenFingerprint(passedToken),
        )

    /** 合格時と同じトークンのままなら、認証情報は通用していると言える。 */
    @Test
    fun sameTokenAsThePassIsConfirmed() {
        assertEquals(
            NtfyCredentialProof.CONFIRMED,
            ntfyCredentialProofOf(config(token = "tk", passedToken = "tk")),
        )
    }

    /** トークンを再発行すると、合格の根拠が失効して要再設定になる。 */
    @Test
    fun tokenReissuedSinceThePassIsStale() {
        assertEquals(
            NtfyCredentialProof.STALE,
            ntfyCredentialProofOf(config(token = "tk_new", passedToken = "tk_old")),
        )
    }

    /** 一度も合格していなければ判断できない。 */
    @Test
    fun neverPassedIsUnconfirmed() {
        assertEquals(
            NtfyCredentialProof.UNCONFIRMED,
            ntfyCredentialProofOf(config(token = "tk", passedToken = null)),
        )
    }

    /** 突き合わせる相手のトークンが無いときは判断を保留する。 */
    @Test
    fun missingCurrentTokenIsUnconfirmed() {
        assertEquals(
            NtfyCredentialProof.UNCONFIRMED,
            ntfyCredentialProofOf(config(token = null, passedToken = "tk_old")),
        )
    }
}
