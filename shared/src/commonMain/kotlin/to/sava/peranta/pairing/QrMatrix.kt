package to.sava.peranta.pairing

/** UI 非依存の QR ドット行列。true が黒モジュール（§10.3 の描画は呼び出し側が担う）。 */
class QrMatrix(val size: Int, private val modules: BooleanArray) {

    /** モジュール ([x], [y]) が黒か。 */
    fun isDark(x: Int, y: Int): Boolean = modules[y * size + x]
}
