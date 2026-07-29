package to.sava.peranta.config

import co.touchlab.kermit.Logger
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt

/**
 * Windows の DPAPI（`CryptProtectData`）で秘密を包む [SecretProtector]（§11）。
 *
 * 保護はログオン中のユーザーアカウントに紐づくため、同じ PC の別ユーザーや、
 * レジストリの値を抜き出しただけの別端末では解けない。対話 UI は出さない
 * （`CRYPTPROTECT_UI_FORBIDDEN`）ので、バックグラウンド稼働中でも待たされない。
 */
object WindowsDpapiProtector : SecretProtector {

    private val log = Logger.withTag("SecretStore")

    /** 往復できるかを確かめるだけの固定値。秘密ではない。 */
    private val PROBE_DATA: ByteArray = "peranta".encodeToByteArray()

    private val isAvailable: Boolean by lazy { probe() }

    override fun protect(data: ByteArray, entropy: ByteArray): ByteArray =
        Crypt32Util.cryptProtectData(data, entropy, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null, null)

    override fun unprotect(data: ByteArray, entropy: ByteArray): ByteArray =
        Crypt32Util.cryptUnprotectData(data, entropy, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null)

    /**
     * この環境で実際に往復できるときだけ自身を返す。Windows 以外・ネイティブライブラリを
     * 読み込めない・DPAPI の呼び出しが失敗する、のいずれでも null を返す。
     */
    fun availableOrNull(): SecretProtector? = if (isAvailable) this else null

    private fun probe(): Boolean {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return false
        return try {
            unprotect(protect(PROBE_DATA, PROBE_DATA), PROBE_DATA)
                .contentEquals(PROBE_DATA)
                .also { if (!it) log.w { "DPAPI round trip mismatch: secrets stay unprotected" } }
        } catch (error: Throwable) {
            // ネイティブライブラリの読み込み失敗は Error で上がるため Throwable で受ける。
            // ここで落ちると起動できなくなるので、保護なしで続行する。
            log.w(error) { "DPAPI unavailable: secrets stay unprotected" }
            false
        }
    }
}
