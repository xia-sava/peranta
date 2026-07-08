package to.sava.peranta.filter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OtpDetectorTest {

    /** 桁数条件とキーワード条件の両方を満たすときだけ OTP とみなす。 */
    @Test
    fun looksLikeOtpRequiresBothDigitsAndKeyword() {
        val cases = mapOf(
            "your code is 123456" to true,
            "確認コード 4821 を入力してください" to true,
            "認証番号: 9087" to true,
            "OTP 55213" to true,
            "ワンタイムパスワードは 246810 です" to true,
            "verification 12345678" to true,
            // キーワードが無い
            "123456 が届きました" to false,
            // 桁数が足りない
            "code 123" to false,
            // 桁数が多すぎる（9 桁）
            "code 123456789" to false,
            // どちらも無い
            "こんにちは" to false,
            // キーワードはあるが数字は年号（誤検知対策）
            "認証システムの更新は2024年を予定" to false,
        )
        cases.forEach { (text, expected) ->
            assertEquals(expected, looksLikeOtp(text), "looksLikeOtp(\"$text\")")
        }
    }

    /** 年号に見える 4 桁（直後が「年」）は OTP コードとみなさない。 */
    @Test
    fun yearLikeDigitsAreNotOtpCode() {
        assertFalse(hasOtpDigits("2024年"))
        assertTrue(hasOtpDigits("2024"))
        assertTrue(hasOtpDigits("コード 2024 を入力"))
    }

    /** 送信元 allowlist が空なら送信元条件を課さず、非空なら含まれる送信元のみ OTP とみなす。 */
    @Test
    fun senderAllowlistGatesOtpDetection() {
        val text = "your code is 123456"
        assertTrue(isOtpNotification(text, "com.example.bank", senderAllowlist = emptyList()))
        assertTrue(isOtpNotification(text, "com.example.bank", senderAllowlist = listOf("com.example.bank")))
        assertFalse(isOtpNotification(text, "com.example.other", senderAllowlist = listOf("com.example.bank")))
    }

    /** 4〜8 桁の独立した数字列だけを OTP コード桁とみなす。 */
    @Test
    fun hasOtpDigitsRespectsBoundaries() {
        assertTrue(hasOtpDigits("abc 1234 xyz"))
        assertTrue(hasOtpDigits("12345678"))
        assertFalse(hasOtpDigits("123"))
        assertFalse(hasOtpDigits("123456789"))
        assertFalse(hasOtpDigits("no digits here"))
    }

    /** キーワード判定はラテン文字の大文字小文字を無視する。 */
    @Test
    fun hasOtpKeywordIsCaseInsensitive() {
        assertTrue(hasOtpKeyword("Your CODE"))
        assertTrue(hasOtpKeyword("Verification link"))
        assertTrue(hasOtpKeyword("認証のお願い"))
        assertFalse(hasOtpKeyword("just a message"))
    }
}
