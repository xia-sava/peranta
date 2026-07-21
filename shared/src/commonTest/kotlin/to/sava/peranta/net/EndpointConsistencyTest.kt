package to.sava.peranta.net

import to.sava.peranta.config.PerantaConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EndpointConsistencyTest {

    private fun config(
        host: String = "peranta.example.com",
        useTls: Boolean = true,
        port: Int? = null,
    ): PerantaConfig = PerantaConfig(host = host, useTls = useTls, port = port)

    /** スキーム・ホスト・ポートが一致すれば Match になる。 */
    @Test
    fun matchingSchemeHostPortIsMatch() {
        val result = matchEndpointServer("https://peranta.example.com/UP/topic", config())
        assertEquals(EndpointServerMatch.Match, result)
    }

    /** ホストが異なれば Mismatch になり、両 origin の表示文字列を持つ。 */
    @Test
    fun differentHostIsMismatchWithOrigins() {
        val result = matchEndpointServer("https://other.example.com/UP/topic", config())
        val mismatch = assertIs<EndpointServerMatch.Mismatch>(result)
        assertEquals("https://other.example.com", mismatch.endpointOrigin)
        assertEquals("https://peranta.example.com", mismatch.configOrigin)
    }

    /** config が非 TLS なのに https エンドポイントを向いていればスキーム不一致で Mismatch になる。 */
    @Test
    fun schemeMismatchIsMismatch() {
        val result = matchEndpointServer(
            "https://peranta.example.com/UP/topic",
            config(useTls = false),
        )
        assertIs<EndpointServerMatch.Mismatch>(result)
    }

    /** 明示された既定ポート（https:443）と省略時は同じものとして正規化され一致する。 */
    @Test
    fun explicitDefaultPortNormalizesToOmittedPort() {
        val result = matchEndpointServer(
            "https://h:443/topic",
            config(host = "h", useTls = true, port = null),
        )
        assertEquals(EndpointServerMatch.Match, result)
    }

    /** 非既定ポート同士は値が一致すれば Match になる。 */
    @Test
    fun nonDefaultPortMatchesWhenEqual() {
        val result = matchEndpointServer(
            "http://h:8090/topic",
            config(host = "h", useTls = false, port = 8090),
        )
        assertEquals(EndpointServerMatch.Match, result)
    }

    /** ホストの大文字小文字は無視して比較する。 */
    @Test
    fun hostCaseIsIgnored() {
        val result = matchEndpointServer(
            "https://PERANTA.EXAMPLE.COM/topic",
            config(host = "peranta.example.com"),
        )
        assertEquals(EndpointServerMatch.Match, result)
    }

    /** URL として解釈できない文字列は Unparseable になる。 */
    @Test
    fun invalidUrlIsUnparseable() {
        val result = matchEndpointServer("https://h:not-a-port/topic", config(host = "h"))
        assertEquals(EndpointServerMatch.Unparseable, result)
    }

    /** スキームを持たない文字列は相対 URL 解釈へ落とさず Unparseable にする。 */
    @Test
    fun schemelessStringIsUnparseable() {
        val result = matchEndpointServer("garbage", config())
        assertEquals(EndpointServerMatch.Unparseable, result)
    }
}
