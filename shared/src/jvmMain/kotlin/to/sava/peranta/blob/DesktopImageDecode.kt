package to.sava.peranta.blob

import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image as SkiaImage

/**
 * 符号化された画像 [bytes] を、展開後の画素数が [MAX_DECODED_IMAGE_PIXELS] に収まるときだけデコードする（§4.3）。
 *
 * Skia の [SkiaImage.makeFromEncoded] は常に等倍で展開するため、渡した時点で確保量が決まる。
 * そこで先に [Codec] でヘッダの寸法だけを読み、上限を超える画像はデコードへ進ませない。
 * Android の縮小デコード（`inSampleSize`）に対応する Desktop 側の歯止めで、
 * 上限そのものは両者が [exceedsDecodedPixelLimit] として共有する。
 *
 * 寸法を読めない・デコードできない場合は null を返す（呼び出し側は種別アイコンへフォールバックする）。
 */
fun decodeImageWithinPixelLimit(bytes: ByteArray): SkiaImage? {
    val dimensions = imageDimensionsOrNull(bytes) ?: return null
    val (width, height) = dimensions
    if (exceedsDecodedPixelLimit(width, height)) return null
    return SkiaImage.makeFromEncoded(bytes)
}

/** 符号化された画像 [bytes] のヘッダから寸法（幅・高さ）を読む。読めなければ null。 */
fun imageDimensionsOrNull(bytes: ByteArray): Pair<Int, Int>? =
    try {
        Data.makeFromBytes(bytes).use { data ->
            Codec.makeFromData(data).use { codec -> codec.width to codec.height }
        }
    } catch (error: RuntimeException) {
        // Skia は壊れたヘッダを実行時例外で返す。画素は 1 つも確保されていないため握って null とする。
        null
    }
