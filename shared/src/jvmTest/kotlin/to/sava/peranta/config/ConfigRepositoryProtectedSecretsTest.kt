package to.sava.peranta.config

import com.russhwolf.settings.MapSettings
import to.sava.peranta.crypto.generateKey
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 保護せずそのまま返す [SecretProtector]。保護の有無ではなく保存場所の移り方を見るために使う。 */
private class PassThroughProtector : SecretProtector {
    override fun protect(data: ByteArray, entropy: ByteArray): ByteArray = data
    override fun unprotect(data: ByteArray, entropy: ByteArray): ByteArray = data
}

class ConfigRepositoryProtectedSecretsTest {

    private val key = Base64.encode(generateKey())

    /** 旧版が settings へ直に書いた共有鍵とアクセストークンを、保護つきの保管庫でもそのまま読める。 */
    @Test
    fun loadsSecretsWrittenByTheOlderVersion() {
        val settings = settingsWithSecretsInTheClear()
        val repo = ConfigRepository(settings, ProtectedSecretStore(settings, PassThroughProtector()))

        val loaded = repo.load()

        assertEquals(key, loaded.sharedKeyBase64)
        assertEquals("tok", loaded.accessToken)
        assertTrue(loaded.hasSharedKey)
    }

    /** 一度読み出すと保護つきの置き場へ移り、素の値は settings から消える。 */
    @Test
    fun movesSecretsOutOfTheClearOnFirstLoad() {
        val settings = settingsWithSecretsInTheClear()
        val repo = ConfigRepository(settings, ProtectedSecretStore(settings, PassThroughProtector()))

        repo.load()

        assertFalse(settings.hasKey(SECRET_SHARED_KEY))
        assertFalse(settings.hasKey(SECRET_ACCESS_TOKEN))
        assertEquals(key, repo.load().sharedKeyBase64)
    }

    /** 「すべての情報の消去」は保護つきの置き場に残った秘密も落とす（§11）。 */
    @Test
    fun clearRemovesProtectedSecrets() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, ProtectedSecretStore(settings, PassThroughProtector()))
        repo.save(PerantaConfig(accessToken = "tok", sharedKeyBase64 = key, keyId = "k1"))

        repo.clear()

        assertTrue(settings.keys.isEmpty())
        assertNull(repo.load().sharedKeyBase64)
        assertNull(repo.load().accessToken)
    }

    /** 旧版の保存形式（秘密名そのままのキーに素の値）を組み立てる。 */
    private fun settingsWithSecretsInTheClear(): MapSettings =
        MapSettings().apply {
            putString(SECRET_SHARED_KEY, key)
            putString(SECRET_ACCESS_TOKEN, "tok")
            putString(ConfigRepository.KEY_KEY_ID, "k1")
            putString(ConfigRepository.KEY_DEVICE_NAME, "desk")
        }
}
