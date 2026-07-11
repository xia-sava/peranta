package to.sava.peranta.android

import kotlin.test.Test
import kotlin.test.assertEquals

class ThumbnailSampleSizeTest {

    /** 目標寸法より小さい画像は間引かない（等倍でデコードする）。 */
    @Test
    fun smallImageIsNotDownsampled() {
        assertEquals(1, thumbnailSampleSize(width = 100, height = 100, reqWidth = 660, reqHeight = 660))
    }

    /** 目標と同寸の画像も間引かない。 */
    @Test
    fun exactSizeIsNotDownsampled() {
        assertEquals(1, thumbnailSampleSize(width = 660, height = 660, reqWidth = 660, reqHeight = 660))
    }

    /** 12MP 級（4000x3000）を 660px 目標へ縮めると 2 の累乗（4）で間引き、フルサイズの確保を避ける。 */
    @Test
    fun largePhotoIsDownsampledToPowerOfTwo() {
        assertEquals(4, thumbnailSampleSize(width = 4000, height = 3000, reqWidth = 660, reqHeight = 660))
    }

    /** 不正な寸法（0 以下）では間引き係数 1 を返し、デコードを壊さない。 */
    @Test
    fun invalidDimensionsFallBackToOne() {
        assertEquals(1, thumbnailSampleSize(width = 0, height = 100, reqWidth = 660, reqHeight = 660))
        assertEquals(1, thumbnailSampleSize(width = 100, height = 100, reqWidth = 0, reqHeight = 660))
    }
}
