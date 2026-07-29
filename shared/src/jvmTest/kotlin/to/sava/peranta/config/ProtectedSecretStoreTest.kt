package to.sava.peranta.config

import com.russhwolf.settings.MapSettings
import to.sava.peranta.platform.RecordingLogWriter
import to.sava.peranta.platform.recordingLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 秘密の値。保護済みの保存値にもログにも現れてはならない。 */
private const val SECRET_VALUE = "secret-never-stored-in-the-clear"

/** バイト列を反転するだけの [SecretProtector]。OS に依存せず包む・解くの往復を再現する。 */
private class ReversingProtector : SecretProtector {
    override fun protect(data: ByteArray, entropy: ByteArray): ByteArray =
        (data + entropy).reversedArray()

    override fun unprotect(data: ByteArray, entropy: ByteArray): ByteArray =
        data.reversedArray().let {
            require(it.takeLast(entropy.size).toByteArray().contentEquals(entropy)) {
                "entropy mismatch"
            }
            it.copyOf(it.size - entropy.size)
        }
}

/** 常に失敗する [SecretProtector]。保護機構が動かない環境を再現する。 */
private class FailingProtector : SecretProtector {
    override fun protect(data: ByteArray, entropy: ByteArray): ByteArray =
        error("protect unavailable")

    override fun unprotect(data: ByteArray, entropy: ByteArray): ByteArray =
        error("unprotect unavailable")
}

private fun protectedKeyOf(name: String) = "$name${ProtectedSecretStore.PROTECTED_SUFFIX}"

class ProtectedSecretStoreTest {

    /** 保存した秘密は読み出しで往復し、settings 上には素の値として現れない。 */
    @Test
    fun storeThenLoadRoundTripsWithoutKeepingPlainValue() {
        val settings = MapSettings()
        val store = ProtectedSecretStore(settings, ReversingProtector())

        store.storeSecret(SECRET_SHARED_KEY, SECRET_VALUE)

        assertEquals(SECRET_VALUE, store.loadSecret(SECRET_SHARED_KEY))
        assertFalse(settings.hasKey(SECRET_SHARED_KEY))
        assertTrue(settings.keys.contains(protectedKeyOf(SECRET_SHARED_KEY)))
        assertFalse(settings.getString(protectedKeyOf(SECRET_SHARED_KEY), "").contains(SECRET_VALUE))
    }

    /**
     * 旧版が素のまま書いた値を読み出せる。読み出した時点で保護済みへ書き直し、素のキーは消える。
     * 既存利用者の共有鍵・トークンが新版で読めなくならないことの担保。
     */
    @Test
    fun loadsValueStoredInTheClearAndProtectsIt() {
        val settings = MapSettings()
        settings.putString(SECRET_ACCESS_TOKEN, SECRET_VALUE)
        val store = ProtectedSecretStore(settings, ReversingProtector())

        assertEquals(SECRET_VALUE, store.loadSecret(SECRET_ACCESS_TOKEN))

        assertFalse(settings.hasKey(SECRET_ACCESS_TOKEN))
        val reopened = ProtectedSecretStore(settings, ReversingProtector())
        assertEquals(SECRET_VALUE, reopened.loadSecret(SECRET_ACCESS_TOKEN))
    }

    /** 保護できない環境でも、素のまま書かれていた値は読めるままで、消えない。 */
    @Test
    fun keepsValueStoredInTheClearWhenProtectionFails() {
        val settings = MapSettings()
        settings.putString(SECRET_ACCESS_TOKEN, SECRET_VALUE)
        val store = ProtectedSecretStore(settings, FailingProtector())

        assertEquals(SECRET_VALUE, store.loadSecret(SECRET_ACCESS_TOKEN))
        assertEquals(SECRET_VALUE, settings.getStringOrNull(SECRET_ACCESS_TOKEN))
    }

    /** 保護済みの値を解けないときは null を返し、理由をログへ残す。秘密そのものはログへ出さない。 */
    @Test
    fun failedUnprotectLogsWithoutLeakingTheSecret() {
        val settings = MapSettings()
        ProtectedSecretStore(settings, ReversingProtector()).storeSecret(SECRET_SHARED_KEY, SECRET_VALUE)
        val writer = RecordingLogWriter()
        val log = recordingLogger(writer, "SecretStore")
        val store = ProtectedSecretStore(settings, FailingProtector(), log)

        assertNull(store.loadSecret(SECRET_SHARED_KEY))

        assertTrue(writer.recorded.any { it.contains(SECRET_SHARED_KEY) })
        assertFalse(writer.recorded.any { it.contains(SECRET_VALUE) })
    }

    /** 消去は保護済みの値と、旧版が素のまま書いた値の両方を落とす（§11）。 */
    @Test
    fun clearRemovesProtectedAndPlainValues() {
        val settings = MapSettings()
        settings.putString(SECRET_SHARED_KEY, SECRET_VALUE)
        settings.putString(protectedKeyOf(SECRET_SHARED_KEY), "stale")
        val store = ProtectedSecretStore(settings, ReversingProtector())

        store.clearSecret(SECRET_SHARED_KEY)

        assertNull(store.loadSecret(SECRET_SHARED_KEY))
        assertFalse(settings.hasKey(SECRET_SHARED_KEY))
        assertFalse(settings.hasKey(protectedKeyOf(SECRET_SHARED_KEY)))
    }

    /** 別の秘密として保存した値は解けない（保護に秘密名を混ぜているため取り違えられない）。 */
    @Test
    fun secretsAreBoundToTheirName() {
        val settings = MapSettings()
        val store = ProtectedSecretStore(settings, ReversingProtector())
        store.storeSecret(SECRET_SHARED_KEY, SECRET_VALUE)

        settings.putString(
            protectedKeyOf(SECRET_ACCESS_TOKEN),
            settings.getString(protectedKeyOf(SECRET_SHARED_KEY), ""),
        )

        assertNull(store.loadSecret(SECRET_ACCESS_TOKEN))
    }
}
