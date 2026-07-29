package to.sava.peranta.update

import java.io.File
import java.security.MessageDigest

/** ダイジェスト計算の読み取り単位。配布物は数十 MB になるため全体をメモリに載せない。 */
private const val DIGEST_BUFFER_BYTES = 64 * 1024

/** [file] の SHA-256 を 16 進表記で返す（§12）。 */
fun sha256HexOf(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DIGEST_BUFFER_BYTES)
    file.inputStream().buffered().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

/**
 * ダウンロードした [file] が [expectedSha256] と一致するかを返す（§12）。
 * 照合できない期待値（空文字）は不一致として扱い、照合を省く経路を作らない。
 */
fun matchesSha256(file: File, expectedSha256: String): Boolean =
    expectedSha256.isNotBlank() && sha256HexOf(file).equals(expectedSha256, ignoreCase = true)
