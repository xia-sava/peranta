package to.sava.peranta.ui.setup

import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.config.accessTokenFingerprint

/**
 * ntfy アプリへ登録した認証情報が、今のアクセストークンで通用すると言えるか（§10.6）。
 * ntfy アプリの設定はこのアプリから覗けないため、判断の根拠は受信テストの合格だけになる。
 */
enum class NtfyCredentialProof {

    /** 今のアクセストークンで受信テストに合格している。 */
    CONFIRMED,

    /** 別のアクセストークンで合格しており、合格の根拠が失効している。 */
    STALE,

    /** 一度も合格しておらず、通用するかを確かめられていない。 */
    UNCONFIRMED,
}

/**
 * [config] が持つ受信テスト合格時のトークン指紋を今のアクセストークンと突き合わせる（§10.6）。
 * 合格の記録が無いとき、および突き合わせる相手のトークンが無いときは判断を保留する。
 */
fun ntfyCredentialProofOf(config: PerantaConfig): NtfyCredentialProof {
    val passed = config.selfTestPassedTokenFingerprint ?: return NtfyCredentialProof.UNCONFIRMED
    val current = accessTokenFingerprint(config.accessToken) ?: return NtfyCredentialProof.UNCONFIRMED
    return if (passed == current) NtfyCredentialProof.CONFIRMED else NtfyCredentialProof.STALE
}
