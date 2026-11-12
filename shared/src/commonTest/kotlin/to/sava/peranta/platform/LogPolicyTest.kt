package to.sava.peranta.platform

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.platformLogWriter
import to.sava.peranta.net.NtfyHistoryException
import to.sava.peranta.net.NtfyPublishException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 実際に採番される長さの topic。末尾以外が残らないことの確認に使う。 */
private const val SAMPLE_TOPIC = "peranta-control-3k9wq2ax7fzb1m4d"

/** 例外メッセージに載りうる接続先 URL。完全な topic を含む。 */
private const val LEAKING_URL = "https://ntfy.example.com/$SAMPLE_TOPIC"

/** 権限で拒まれたときの HTTP ステータス。 */
private const val HTTP_FORBIDDEN = 403

/** 流量で拒まれたときの HTTP ステータス。 */
private const val HTTP_TOO_MANY_REQUESTS = 429

/**
 * ログに残すものの方針。設定に応じて最小重大度が変わることと、
 * 完全な topic 名がログ用の表現に残らないことを固定する。
 */
class LogPolicyTest {

    /** グローバルの Logger を書き換えるため、他のテストへ影響を残さないよう既定へ戻す。 */
    @AfterTest
    fun restoreGlobalLogger() {
        configurePerantaLogging(verboseLogging = true, platformLogWriter())
    }

    /** 既定（詳細な記録が OFF）では Info 未満を残さない。 */
    @Test
    fun defaultVerbosityKeepsInfoAndAbove() {
        applyLogVerbosity(verboseLogging = false)
        assertEquals(Severity.Info, Logger.config.minSeverity)
    }

    /** 詳細な記録を ON にすると Verbose まで残す。 */
    @Test
    fun verboseLoggingKeepsEverything() {
        applyLogVerbosity(verboseLogging = true)
        assertEquals(Severity.Verbose, Logger.config.minSeverity)
    }

    /** 出力先の設定と最小重大度の適用は同じ入口で済み、既定では debug の行が届かない。 */
    @Test
    fun configuringLoggingInstallsWritersAndSeverity() {
        val writer = RecordingLogWriter()

        configurePerantaLogging(verboseLogging = false, writer)
        Logger.withTag("Policy").i { "kept" }
        Logger.withTag("Policy").d { "dropped" }

        assertEquals(Severity.Info, Logger.config.minSeverity)
        assertEquals(listOf(true), writer.recorded.map { it.contains("kept") })
    }

    /** ログ用の topic 表現には末尾の数文字しか残らない。 */
    @Test
    fun topicForLogKeepsOnlyTail() {
        val shortened = topicForLog(SAMPLE_TOPIC)

        assertEquals("…${SAMPLE_TOPIC.takeLast(4)}", shortened)
        assertFalse(shortened.contains(SAMPLE_TOPIC), "full topic must not appear")
        assertTrue(SAMPLE_TOPIC.endsWith(shortened.removePrefix("…")), "tail must come from the topic")
    }

    /** 末尾を残せない短い topic は、残す文字を持たない表現になる。 */
    @Test
    fun topicForLogHidesTopicsTooShortToTruncate() {
        assertEquals("…", topicForLog("abcd"))
        assertEquals("…", topicForLog(""))
    }

    /** publish の失敗は、種別名と HTTP ステータスだけが残り、例外メッセージは残らない。 */
    @Test
    fun failureForLogKeepsStatusOfPublishException() {
        val shown = failureForLog(NtfyPublishException(HTTP_FORBIDDEN, "POST $LEAKING_URL failed"))

        assertEquals("NtfyPublishException status=403", shown)
    }

    /** 履歴取得の失敗も、種別名と HTTP ステータスだけが残り、例外メッセージは残らない。 */
    @Test
    fun failureForLogKeepsStatusOfHistoryException() {
        val shown = failureForLog(NtfyHistoryException(HTTP_TOO_MANY_REQUESTS, "GET $LEAKING_URL failed"))

        assertEquals("NtfyHistoryException status=429", shown)
    }

    /** 自前以外の例外は種別名だけが残り、メッセージ中の接続先 URL は残らない。 */
    @Test
    fun failureForLogKeepsOnlyTypeNameOfOtherExceptions() {
        val shown = failureForLog(IllegalStateException("Request timeout has expired [url=$LEAKING_URL]"))

        assertEquals("IllegalStateException", shown)
        assertFalse(shown.contains(SAMPLE_TOPIC), "topic must not appear")
    }
}
