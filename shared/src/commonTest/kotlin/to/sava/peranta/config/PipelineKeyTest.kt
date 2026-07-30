package to.sava.peranta.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PipelineKeyTest {

    private val base = PerantaConfig(
        keyId = "1",
        sharedKeyBase64 = "AAAA",
        sendEnabled = true,
        persistSensitiveHistory = true,
        deviceId = "dev-1",
    )

    /** 対象フィールドがそのまま抽出される。 */
    @Test
    fun extractsPipelineFields() {
        val key = base.toPipelineKey()
        assertEquals("1", key.keyId)
        assertEquals("AAAA", key.sharedKeyBase64)
        assertEquals(true, key.sendEnabled)
        assertEquals(true, key.persistSensitiveHistory)
        assertEquals("dev-1", key.deviceId)
    }

    /** 対象フィールドが同じなら等価（data class の equals）。 */
    @Test
    fun equalWhenPipelineFieldsUnchanged() {
        assertEquals(
            base.toPipelineKey(),
            base.copy(host = "other", accessToken = "tk", port = 9000).toPipelineKey(),
        )
    }

    /** 対象フィールドのいずれかが変われば非等価。 */
    @Test
    fun differsWhenAnyPipelineFieldChanges() {
        assertNotEquals(base.toPipelineKey(), base.copy(keyId = "2").toPipelineKey())
        assertNotEquals(base.toPipelineKey(), base.copy(sharedKeyBase64 = "BBBB").toPipelineKey())
        assertNotEquals(base.toPipelineKey(), base.copy(sendEnabled = false).toPipelineKey())
        assertNotEquals(base.toPipelineKey(), base.copy(persistSensitiveHistory = false).toPipelineKey())
        assertNotEquals(base.toPipelineKey(), base.copy(deviceId = "dev-2").toPipelineKey())
    }

    /** 文字列化に共有鍵は現れない。ログ・例外メッセージ・assert 出力への混入を防ぐ。 */
    @Test
    fun doesNotExposeSharedKeyWhenStringified() {
        val text = base.toPipelineKey().toString()
        assertFalse(text.contains("AAAA"), "共有鍵が文字列化されている: $text")
        assertTrue(text.contains("keyId=1"), "鍵以外の値は読める: $text")
    }
}
