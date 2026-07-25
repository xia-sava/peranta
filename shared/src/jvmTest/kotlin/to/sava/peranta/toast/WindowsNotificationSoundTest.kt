package to.sava.peranta.toast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** サウンド設定の読み取りに使う純粋ロジック（レジストリ出力の解析と環境変数の展開）を検証する。 */
class WindowsNotificationSoundTest {

    /** 値の型に続く残りを既定値として取り出す。 */
    @Test
    fun parsesAssignedSoundPath() {
        val output = """
            HKEY_CURRENT_USER\AppEvents\Schemes\Apps\.Default\Notification.Default\.Current
                (既定)    REG_EXPAND_SZ    C:\WINDOWS\media\Windows Notify System Generic.wav
        """.trimIndent()

        assertEquals(
            """C:\WINDOWS\media\Windows Notify System Generic.wav""",
            parseRegistryDefaultValue(output),
        )
    }

    /** 値が空（サウンドなしを選んでいる状態）なら null。 */
    @Test
    fun returnsNullWhenNoSoundIsAssigned() {
        assertNull(parseRegistryDefaultValue("    (既定)    REG_SZ    "))
    }

    /** キーそのものが無いときの出力でも null。 */
    @Test
    fun returnsNullWhenKeyIsMissing() {
        assertNull(parseRegistryDefaultValue("エラー: 指定されたレジストリ キーまたは値が見つかりませんでした。"))
    }

    /** %VAR% を環境変数の値へ展開する。 */
    @Test
    fun expandsEnvironmentReferences() {
        val expanded = expandEnvironmentReferences("""%SystemRoot%\media\ding.wav""") { name ->
            if (name == "SystemRoot") """C:\WINDOWS""" else null
        }

        assertEquals("""C:\WINDOWS\media\ding.wav""", expanded)
    }

    /** 解決できない参照はそのまま残す。 */
    @Test
    fun keepsUnresolvedReferences() {
        assertEquals(
            """%Unknown%\ding.wav""",
            expandEnvironmentReferences("""%Unknown%\ding.wav""") { null },
        )
    }
}
