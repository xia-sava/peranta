package to.sava.peranta.blob

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Desktop の画像デコードは、符号化サイズではなく展開後の画素数で歯止めを持つ（§4.3）。
 * Android の縮小デコードに対応する対策で、上限は [exceedsDecodedPixelLimit] として共有する。
 */
class DesktopImageDecodeTest {

    /** PNG の IHDR チャンク（署名 8 バイトの直後）で幅・高さが始まる位置。 */
    private val ihdrWidthOffset = 16

    /** IHDR のチャンク型 + データ（CRC の計算範囲）が始まる位置。 */
    private val ihdrCrcRangeOffset = 12

    /** IHDR のチャンク型 + データの長さ（"IHDR" 4 バイト + データ 13 バイト）。 */
    private val ihdrCrcRangeLength = 17

    /**
     * 実在する小さな PNG の IHDR だけを [width]×[height] へ書き換える。
     * 画素データは 1×1 のまま残るので、宣言した寸法のバイト列を用意せずに
     * 「ヘッダだけが巨大な画像」を作れる。
     */
    private fun pngDeclaring(width: Int, height: Int): ByteArray =
        realPng(1, 1).also { png ->
            png.writeInt(ihdrWidthOffset, width)
            png.writeInt(ihdrWidthOffset + 4, height)
            val crc = CRC32().apply { update(png, ihdrCrcRangeOffset, ihdrCrcRangeLength) }.value.toInt()
            png.writeInt(ihdrCrcRangeOffset + ihdrCrcRangeLength, crc)
        }

    private fun ByteArray.writeInt(offset: Int, value: Int) {
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = value.toByte()
    }

    private fun realPng(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    /** 通常の大きさの画像はこれまでどおりデコードできる。 */
    @Test
    fun ordinaryImageDecodes() {
        val decoded = assertNotNull(decodeImageWithinPixelLimit(realPng(64, 48)))
        assertEquals(64, decoded.width)
        assertEquals(48, decoded.height)
    }

    /** 符号化データを展開せずにヘッダの寸法だけを読める。 */
    @Test
    fun dimensionsComeFromTheHeader() {
        assertEquals(30_000 to 30_000, imageDimensionsOrNull(pngDeclaring(30_000, 30_000)))
    }

    /**
     * 上限を超える寸法を宣言した画像はデコードしない。
     * 30000×30000 は 9 億画素（32bit で約 3.6GB）で、等倍で展開すればメモリを食い潰す。
     * ここが null で戻ることが、確保が起きていないことの表明になる。
     */
    @Test
    fun oversizedImageIsNotDecoded() {
        assertNull(decodeImageWithinPixelLimit(pngDeclaring(30_000, 30_000)))
    }

    /** ヘッダを読めないバイト列はデコードへ進ませない。 */
    @Test
    fun malformedBytesAreRejected() {
        assertNull(imageDimensionsOrNull(byteArrayOf(1, 2, 3, 4)))
        assertNull(decodeImageWithinPixelLimit(byteArrayOf(1, 2, 3, 4)))
        assertNull(decodeImageWithinPixelLimit(ByteArray(0)))
    }
}
