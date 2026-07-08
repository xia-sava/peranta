package to.sava.peranta.config

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyStoreJvmTest {

    /** jvm の createKeyStore は SettingsKeyStore を返し、鍵の保存・読み出し・消去が往復する。 */
    @Test
    fun createKeyStoreStoresLoadsAndClears() {
        val store = createKeyStore(MapSettings())
        assertTrue(store is SettingsKeyStore)
        assertNull(store.loadKey())

        val key = ByteArray(32) { it.toByte() }
        store.storeKey(key)
        assertContentEquals(key, store.loadKey())

        store.clearKey()
        assertNull(store.loadKey())
    }
}
