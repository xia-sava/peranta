package to.sava.peranta.config

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** この環境で DPAPI を使えるか。使えない環境では DPAPI 依存のテストを飛ばす。 */
private val dpapiAvailable: Boolean = WindowsDpapiProtector.availableOrNull() != null

class SecretStoreJvmTest {

    /** createSecretStore が返す保管庫は、DPAPI の使える環境でもそうでなくても秘密を往復させる。 */
    @Test
    fun createSecretStoreStoresLoadsAndClears() {
        val store = createSecretStore(MapSettings())
        assertNull(store.loadSecret(SECRET_SHARED_KEY))

        store.storeSecret(SECRET_SHARED_KEY, "a2V5")
        assertEquals("a2V5", store.loadSecret(SECRET_SHARED_KEY))

        store.clearSecret(SECRET_SHARED_KEY)
        assertNull(store.loadSecret(SECRET_SHARED_KEY))
    }

    /** DPAPI が使える環境では保護つきの保管庫を返し、素の値を settings へ残さない（§11）。 */
    @Test
    fun createSecretStoreProtectsWhenDpapiIsAvailable() {
        if (!dpapiAvailable) return
        val settings = MapSettings()

        createSecretStore(settings).storeSecret(SECRET_ACCESS_TOKEN, "tok-never-stored-in-the-clear")

        assertFalse(settings.hasKey(SECRET_ACCESS_TOKEN))
        assertTrue(settings.keys.contains("$SECRET_ACCESS_TOKEN${ProtectedSecretStore.PROTECTED_SUFFIX}"))
    }

    /** DPAPI で包んだ値は同じ利用者のプロセスで解け、包んだ結果には元のバイト列が現れない。 */
    @Test
    fun dpapiRoundTripsAndHidesThePlainBytes() {
        if (!dpapiAvailable) return
        val secret = "shared-key-never-stored-in-the-clear".encodeToByteArray()
        val entropy = "peranta-test".encodeToByteArray()

        val protectedBytes = WindowsDpapiProtector.protect(secret, entropy)

        assertFalse(protectedBytes.asList().windowed(secret.size).contains(secret.asList()))
        assertContentEquals(secret, WindowsDpapiProtector.unprotect(protectedBytes, entropy))
    }

    /** DPAPI は追加エントロピーが一致しないと解けない。 */
    @Test
    fun dpapiRejectsDifferentEntropy() {
        if (!dpapiAvailable) return
        val protectedBytes = WindowsDpapiProtector.protect(
            "shared-key-never-stored-in-the-clear".encodeToByteArray(),
            "peranta-test".encodeToByteArray(),
        )

        assertFalse(
            runCatching {
                WindowsDpapiProtector.unprotect(protectedBytes, "other-entropy".encodeToByteArray())
            }.isSuccess,
        )
    }
}
