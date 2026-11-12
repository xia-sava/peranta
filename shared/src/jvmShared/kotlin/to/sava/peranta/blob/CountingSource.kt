package to.sava.peranta.blob

import java.io.FilterInputStream
import java.io.InputStream

/**
 * 送るファイルの本体を読みながら、読んだバイト数を知らせる入力ストリーム（§4.3）。
 * 配列へ読み出す `read` で 1 バイト以上読めたたびに、その回に読んだバイト数を [onRead] へ渡す。
 * 1 バイトずつ読む `read()` と `skip` で進んだ分は数えない。
 * 閉じると [source] も閉じる。Windows は掴んだままのファイルを消せないため、使い終えたら必ず閉じる。
 */
class CountingSource(
    source: InputStream,
    private val onRead: (byteCount: Long) -> Unit,
) : FilterInputStream(source) {

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { read -> if (read > 0) onRead(read.toLong()) }
}
