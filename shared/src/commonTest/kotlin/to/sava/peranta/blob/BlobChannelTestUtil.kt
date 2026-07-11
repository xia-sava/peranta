package to.sava.peranta.blob

import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writer
import kotlinx.coroutines.coroutineScope
import kotlinx.io.readByteArray

/**
 * [write] が書き出したバイト列をメモリに集める（テスト用）。
 * writer コルーチンで書き手と読み手を並行させ、チャンクサイズを超える出力でも詰まらせない。
 */
internal suspend fun drainToBytes(write: suspend (ByteWriteChannel) -> Unit): ByteArray = coroutineScope {
    writer { write(channel) }.channel.readRemaining().readByteArray()
}
