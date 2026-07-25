package to.sava.peranta

import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * 同梱アイコンの読み込み口。意匠は `tools/icons/generate_icons.py` が生成し、
 * 各サイズの PNG をリソースへ同梱する。
 */
object PerantaIcon {

    /** 同梱してある一辺の px。昇順に持ち、要求サイズ以上で最小のものを選ぶ。 */
    private val availableSizes = listOf(16, 24, 32, 48, 256)

    fun image(preferredSize: Int): BufferedImage {
        val size = availableSizes.firstOrNull { it >= preferredSize } ?: availableSizes.last()
        val path = "/icons/peranta-$size.png"
        val stream = checkNotNull(javaClass.getResourceAsStream(path)) { "icon resource not found: $path" }
        return stream.use { ImageIO.read(it) }
    }
}
