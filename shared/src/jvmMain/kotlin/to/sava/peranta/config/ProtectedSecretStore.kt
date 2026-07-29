package to.sava.peranta.config

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import kotlin.io.encoding.Base64

/** 秘密のバイト列を OS の保護機構で包む・解く。 */
interface SecretProtector {
    fun protect(data: ByteArray, entropy: ByteArray): ByteArray
    fun unprotect(data: ByteArray, entropy: ByteArray): ByteArray
}

/**
 * [SecretProtector] で包んでから settings へ書く [SecretStore]（§11）。
 * 保護済みの値は秘密名に [PROTECTED_SUFFIX] を付けたキーへ base64 で置き、秘密名そのままのキーは使わない。
 *
 * 素のまま保存する実装が書いた値は秘密名そのままのキーに残るため、読み出しでそれを見つけたら
 * 保護済みへ書き直して素のキーを消す。書き直しに失敗したときは素の値を残す。読めなくなると
 * ペアリングのやり直しになるため、保護できないことより読めることを優先する。
 */
class ProtectedSecretStore(
    private val settings: Settings,
    private val protector: SecretProtector,
    private val log: Logger = Logger.withTag("SecretStore"),
) : SecretStore {

    override fun loadSecret(name: String): String? =
        settings.getStringOrNull(protectedKey(name))?.let { unprotect(name, it) }
            ?: settings.getStringOrNull(name)?.also { protectInPlace(name, it) }

    override fun storeSecret(name: String, value: String) {
        settings.putString(
            protectedKey(name),
            Base64.encode(protector.protect(value.encodeToByteArray(), entropyFor(name))),
        )
        settings.remove(name)
    }

    override fun clearSecret(name: String) {
        settings.remove(protectedKey(name))
        settings.remove(name)
    }

    /** 保護済みの値を解く。解けないときは理由をログに残して null を返す（値そのものは出さない）。 */
    private fun unprotect(name: String, stored: String): String? =
        runCatching { protector.unprotect(Base64.decode(stored), entropyFor(name)).decodeToString() }
            .onFailure { log.e(it) { "cannot unprotect stored secret: $name" } }
            .getOrNull()

    /** 素のまま置かれていた値を保護済みへ書き直す。失敗しても素の値は消さない。 */
    private fun protectInPlace(name: String, value: String) {
        runCatching { storeSecret(name, value) }
            .onFailure { log.w(it) { "secret stays unprotected: $name" } }
    }

    /**
     * 保護に混ぜる追加エントロピー。秘密ではなく、他のアプリが作った blob や
     * 別の秘密の blob を取り違えて解けないようにするための束縛。
     */
    private fun entropyFor(name: String): ByteArray = "$ENTROPY_PREFIX$name".encodeToByteArray()

    private fun protectedKey(name: String): String = "$name$PROTECTED_SUFFIX"

    companion object {
        /** 保護済みの値を置くキーの接尾辞。素のまま保存された値と混ざらないよう別のキーにする。 */
        const val PROTECTED_SUFFIX: String = ".protected"

        private const val ENTROPY_PREFIX: String = "to.sava.peranta/secret/"
    }
}
