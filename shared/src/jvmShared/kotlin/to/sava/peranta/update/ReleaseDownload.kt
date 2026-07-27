package to.sava.peranta.update

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URISyntaxException

/** 取得を許す URL スキーム。 */
private val ALLOWED_SCHEMES = setOf("http", "https")

/** 書き写しの読み取り単位。 */
private const val COPY_BUFFER_BYTES = 64 * 1024

/** 受信量を知らせる間隔。1 バイトごとに知らせると表示が過剰に更新されるため間引く。 */
private const val PROGRESS_NOTIFY_BYTES = 512L * 1024

/**
 * [url] が http/https かつホストを持つ、取得してよい形式かを判定する（純粋関数）。
 * latest.json 由来の外部入力を扱う前の検証に使う。
 */
fun isBrowsableHttpUrl(url: String): Boolean {
    val uri = try {
        URI(url)
    } catch (e: URISyntaxException) {
        return false
    }
    return uri.scheme?.lowercase() in ALLOWED_SCHEMES && !uri.host.isNullOrEmpty()
}

/**
 * [url] の中身を [file] へ書き出しながら、受信量を [onProgress] へ知らせる（§12）。
 *
 * 応答を受け取りながら書き出す。ボディを一度メモリへ載せる読み方をすると、数十 MB の配布物が
 * まるごとヒープに載るうえ、読み終わるまで受信量を知らせられず進捗が出ない。
 */
suspend fun HttpClient.downloadToFile(
    url: String,
    file: File,
    onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
) {
    if (!isBrowsableHttpUrl(url)) {
        throw IOException("download url rejected: not a http(s) url")
    }
    prepareGet(url).execute { response ->
        if (!response.status.isSuccess()) {
            throw IOException("download failed: HTTP ${response.status.value}")
        }
        val total = response.contentLength() ?: 0L
        response.bodyAsChannel().toInputStream().use { input ->
            file.outputStream().buffered().use { output ->
                copyReportingProgress(input, output, total, onProgress)
            }
        }
    }
}

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
