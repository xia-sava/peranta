package to.sava.peranta

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.model.decodeEnvelope
import to.sava.peranta.net.createNtfyHttpClient
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 送信側 e2e 用の復号検証ツール（§16）。ローカル ntfy の topic をポーリングし、
 * アプリが publish した Envelope を復号して期待文字列が含まれるかを assert する。
 * 環境変数 PERANTA_VERIFY=1 のときだけ実行する。
 * 必要な環境変数: PERANTA_VERIFY_TOPIC / PERANTA_VERIFY_KEY(base64) / PERANTA_VERIFY_EXPECT。
 * 任意: PERANTA_VERIFY_KEYID(既定 k1) / PERANTA_VERIFY_PORT(既定 8090)。
 */
class SendVerifyToolTest {

    @Test
    fun decryptsPublishedEnvelope() {
        assumeTrue("PERANTA_VERIFY!=1 のためスキップ", System.getenv("PERANTA_VERIFY") == "1")
        val keyB64 = requireEnv("PERANTA_VERIFY_KEY")
        val expect = requireEnv("PERANTA_VERIFY_EXPECT")
        val keyId = System.getenv("PERANTA_VERIFY_KEYID") ?: "k1"
        val cipher = MessageCipher(Base64.decode(keyB64), keyId)

        // 事前捕捉した封筒ファイル（1 行 1 封筒 JSON）を復号する（短キャッシュ回避）。
        System.getenv("PERANTA_VERIFY_ENVELOPE_FILE")?.let { path ->
            runBlocking {
                val payloads = buildList {
                    java.io.File(path).readLines().filter { it.isNotBlank() }.forEach { line ->
                        runCatching { cipher.open(decodeEnvelope(line)) }.getOrNull()?.let { add(it) }
                    }
                }
                payloads.forEach { println("DECRYPTED: $it") }
                assertTrue(
                    payloads.any { it.toString().contains(expect) },
                    "期待文字列 '$expect' を含む復号 payload が見つかりません。復号 ${payloads.size} 件",
                )
            }
            return
        }

        val topic = requireEnv("PERANTA_VERIFY_TOPIC")
        val port = System.getenv("PERANTA_VERIFY_PORT") ?: "8090"
        val token = readToken()
        val json = Json { ignoreUnknownKeys = true }

        runBlocking {
            val http = createNtfyHttpClient()
            try {
                val url = "http://localhost:$port/$topic/json?poll=1&since=all"
                val body = http.get(url) {
                    token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                }.bodyAsText()

                val messages = body.lineSequence()
                    .filter { it.isNotBlank() }
                    .mapNotNull { line ->
                        runCatching {
                            json.parseToJsonElement(line).jsonObject["message"]?.jsonPrimitive?.content
                        }.getOrNull()
                    }
                    .toList()

                val decrypted = buildList {
                    for (message in messages) {
                        runCatching { cipher.open(decodeEnvelope(message)) }.getOrNull()?.let { add(it) }
                    }
                }

                decrypted.forEach { println("DECRYPTED: $it") }
                assertTrue(
                    decrypted.any { it.toString().contains(expect) },
                    "期待文字列 '$expect' を含む復号 payload が見つかりません。復号 ${decrypted.size} 件",
                )
            } finally {
                http.close()
            }
        }
    }

    private fun requireEnv(name: String): String =
        System.getenv(name) ?: error("環境変数 $name が未設定です")

    private fun readToken(): String? {
        var dir = java.io.File(System.getProperty("user.dir"))
        repeat(5) {
            val candidate = java.io.File(dir, "server/.local/credentials.txt")
            if (candidate.exists()) {
                return candidate.readLines()
                    .firstOrNull { it.startsWith("token=") }
                    ?.substringAfter("token=")
                    ?.trim()
            }
            dir = dir.parentFile ?: return null
        }
        return null
    }
}
