package to.sava.peranta.update

import java.io.InputStream
import java.io.OutputStream

/** 書き写しの読み取り単位。 */
private const val COPY_BUFFER_BYTES = 64 * 1024

/** 受信量を知らせる間隔。1 バイトごとに知らせると表示が過剰に更新されるため間引く。 */
private const val PROGRESS_NOTIFY_BYTES = 512L * 1024

/**
 * [input] を [output] へ書き写しながら、受信量を [onProgress] へ知らせる（§12）。
 * 通知は一定量ごとに間引き、書き写しの完了時には必ず 1 回知らせる。
 * [total] は全体長で、判らないときは 0 をそのまま渡す。
 */
fun copyReportingProgress(
    input: InputStream,
    output: OutputStream,
    total: Long,
    onProgress: (received: Long, total: Long) -> Unit,
) {
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var received = 0L
    var notified = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
        received += read
        if (received - notified >= PROGRESS_NOTIFY_BYTES) {
            notified = received
            onProgress(received, total)
        }
    }
    onProgress(received, total)
}
