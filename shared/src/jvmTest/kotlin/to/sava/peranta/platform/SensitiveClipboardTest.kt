package to.sava.peranta.platform

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.SystemFlavorMap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 秘密を含む文字列のクリップボード転送物（§10.3）。実クリップボードは環境依存で他の作業を壊すため、
 * 転送物そのものが「本文」と「Windows へ除外を求める形式」の両方を載せることを確かめる。
 */
class SensitiveClipboardTest {

    /** Windows が履歴・クラウド同期の可否を判断する形式名。転送物はこの 3 つを載せる。 */
    private val exclusionFormats = setOf(
        "ExcludeClipboardContentFromMonitorProcessing",
        "CanIncludeInClipboardHistory",
        "CanUploadToCloudClipboard",
    )

    /** 本文以外に載せている形式（アプリ独自の MIME 型で見分ける）。 */
    private fun exclusionFlavorsOf(flavors: Array<DataFlavor>): List<DataFlavor> =
        flavors.filter { it.subType.startsWith("x-peranta-") }

    /** 通常の貼り付けが壊れないこと。文字列としての中身はそのまま取り出せる。 */
    @Test
    fun transferableStillCarriesPlainText() {
        val transferable = sensitiveTextTransferable("peranta://pair?k=secret")

        assertTrue(transferable.isDataFlavorSupported(DataFlavor.stringFlavor))
        assertEquals("peranta://pair?k=secret", transferable.getTransferData(DataFlavor.stringFlavor))
    }

    /** 除外を求める 3 形式が載っており、それぞれ Windows の形式名へ対応づけられている。 */
    @Test
    fun transferableRequestsExclusionFromClipboardHistory() {
        val flavorMap = SystemFlavorMap.getDefaultFlavorMap() as SystemFlavorMap

        val flavors = exclusionFlavorsOf(sensitiveTextTransferable("secret").transferDataFlavors)

        assertEquals(exclusionFormats, flavors.flatMap { flavorMap.getNativesForFlavor(it) }.toSet())
    }

    /** 除外形式の値は DWORD の 0（＝履歴にもクラウドにも入れない）で、バイト列としてそのまま渡る。 */
    @Test
    fun exclusionFlavorsCarryZeroDword() {
        val transferable = sensitiveTextTransferable("secret")

        exclusionFlavorsOf(transferable.transferDataFlavors).forEach { flavor ->
            assertEquals(ByteArray::class.java, flavor.representationClass)
            assertContentEquals(ByteArray(4), transferable.getTransferData(flavor) as ByteArray)
        }
    }
}
