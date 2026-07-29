package to.sava.peranta.blob

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 画像デコードの画素数上限（§4.3）。Android の縮小デコードと Desktop の等倍デコードが同じ値を見る。 */
class ImageDecodeLimitsTest {

    /** 実際に撮られた写真の画素数は通す（数千万画素）。 */
    @Test
    fun realPhotoDimensionsPass() {
        assertFalse(exceedsDecodedPixelLimit(4000, 3000))
        assertFalse(exceedsDecodedPixelLimit(8000, 6000))
    }

    /** 圧縮率だけを利用した巨大な寸法は拒否する（30000×30000 で 9 億画素）。 */
    @Test
    fun decompressionBombDimensionsAreRejected() {
        assertTrue(exceedsDecodedPixelLimit(30_000, 30_000))
    }

    /** 極端な縦横比でも画素数の総量で判定する（片方が小さくても総量が上限を超えれば拒否）。 */
    @Test
    fun extremeAspectRatioIsJudgedByTotalPixels() {
        assertTrue(exceedsDecodedPixelLimit(1_000_000, 100))
        assertFalse(exceedsDecodedPixelLimit(1_000_000, 60))
    }

    /** 上限ちょうどは通し、1 画素超えたら拒否する。 */
    @Test
    fun theLimitItselfPasses() {
        assertFalse(exceedsDecodedPixelLimit(8192, 8192))
        assertTrue(exceedsDecodedPixelLimit(8193, 8192))
    }

    /** 寸法を読めなかった場合（0 以下）はデコードへ進ませない。 */
    @Test
    fun unreadableDimensionsAreRejected() {
        assertTrue(exceedsDecodedPixelLimit(0, 100))
        assertTrue(exceedsDecodedPixelLimit(100, 0))
        assertTrue(exceedsDecodedPixelLimit(-1, -1))
    }
}
