package to.sava.peranta.platform

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.SystemFlavorMap
import java.awt.datatransfer.Transferable

/**
 * Windows がクリップボードの内容を履歴（Win+V）とクラウド同期へ載せるかを判断するクリップボード形式。
 * 本文と同じ転送に載せると除外を要求できる（§10.3）。値は左が [DataFlavor] の MIME 型、右が形式名。
 */
private val EXCLUSION_FORMATS: Map<String, String> = mapOf(
    "application/x-peranta-clipboard-monitor" to "ExcludeClipboardContentFromMonitorProcessing",
    "application/x-peranta-clipboard-history" to "CanIncludeInClipboardHistory",
    "application/x-peranta-cloud-clipboard" to "CanUploadToCloudClipboard",
)

/** 除外を要求する値（DWORD の 0）。「履歴へ入れてよいか」「クラウドへ上げてよいか」の答えが偽になる。 */
private val EXCLUSION_VALUE: ByteArray = ByteArray(4)

/**
 * 除外を要求する [DataFlavor] 群。AWT は [SystemFlavorMap] に登録された形式名を
 * `RegisterClipboardFormat` へ通すため、[DataFlavor] と形式名の対応をここで結んでおく。
 */
private val exclusionFlavors: List<DataFlavor> by lazy {
    val flavorMap = SystemFlavorMap.getDefaultFlavorMap() as SystemFlavorMap
    EXCLUSION_FORMATS.map { (mimeType, format) ->
        DataFlavor("$mimeType; class=\"[B\"", format)
            .also { flavor -> flavorMap.addUnencodedNativeForFlavor(flavor, format) }
    }
}

/**
 * 秘密を含む文字列の転送物。通常のテキストに加えて [EXCLUSION_FORMATS] を載せ、
 * Windows へ履歴・クラウド同期からの除外を求める。
 */
private class SensitiveTextSelection(private val text: StringSelection) : Transferable {

    override fun getTransferDataFlavors(): Array<DataFlavor> =
        text.transferDataFlavors + exclusionFlavors

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
        transferDataFlavors.any { it == flavor }

    override fun getTransferData(flavor: DataFlavor): Any =
        if (flavor in exclusionFlavors) EXCLUSION_VALUE.copyOf() else text.getTransferData(flavor)
}

/** [text] をクリップボードへ置くための転送物を組み立てる。 */
internal fun sensitiveTextTransferable(text: String): Transferable = SensitiveTextSelection(StringSelection(text))

/**
 * 秘密を含む [text] をシステムクリップボードへ置く（§10.3）。
 *
 * 履歴とクラウド同期からの除外を併せて要求するが、要求を尊重するかは OS と常駐する
 * クリップボード管理ソフト次第で、貼り付け先に平文が残ることも防げない。
 * 利用者へは残りうる前提の注意を UI で伝える。
 */
fun copySensitiveTextToClipboard(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(sensitiveTextTransferable(text), null)
}
