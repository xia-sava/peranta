package to.sava.peranta.filter

/**
 * OTP らしさ検出（§7）。保守的に「4〜8 桁の数字コード」かつ「認証系キーワード」の
 * 両方を満たす場合のみ true とし、誤検知を抑える。
 */

/** OTP を示すキーワード。ラテン文字は小文字化して部分一致で判定する。 */
private val OTP_KEYWORDS: List<String> = listOf(
    "code",
    "verification",
    "otp",
    "認証",
    "確認コード",
    "ワンタイム",
)

/** 連続数字の桁数がこの範囲なら OTP コードとみなす。 */
private val OTP_DIGIT_RANGE: IntRange = 4..8

/** 連続する数字列を位置つきで切り出す正規表現。 */
private val DIGIT_RUN: Regex = Regex("\\d+")

/** 年号を表す接尾辞。この直後の 4 桁は OTP コードではなく年号とみなす（誤検知対策）。 */
private const val YEAR_SUFFIX: Char = '年'

/**
 * [text] に 4〜8 桁の独立した数字列が含まれるか。9 桁以上の連番は該当しない。
 * 「2024年」のように年号に見える 4 桁（直後が年）は OTP コードとみなさない。
 */
fun hasOtpDigits(text: String): Boolean =
    DIGIT_RUN.findAll(text).any { match ->
        match.value.length in OTP_DIGIT_RANGE && !looksLikeYear(text, match)
    }

/** 4 桁で直後が年号接尾辞なら年号とみなす。 */
private fun looksLikeYear(text: String, match: MatchResult): Boolean =
    match.value.length == 4 && text.getOrNull(match.range.last + 1) == YEAR_SUFFIX

/** [text] に OTP キーワードが含まれるか（ラテン文字は大文字小文字を無視）。 */
fun hasOtpKeyword(text: String): Boolean {
    val lower = text.lowercase()
    return OTP_KEYWORDS.any { lower.contains(it) }
}

/** [text] が OTP らしいか。桁数条件とキーワード条件の両方を満たす場合に true。 */
fun looksLikeOtp(text: String): Boolean =
    hasOtpDigits(text) && hasOtpKeyword(text)

/**
 * 通知が OTP らしいか（§7）。[looksLikeOtp] に加え、[senderAllowlist] が非空のときは
 * [packageName] が allowlist に含まれる場合に限る（空なら送信元パッケージ条件を課さない）。
 */
fun isOtpNotification(text: String, packageName: String, senderAllowlist: List<String>): Boolean {
    if (senderAllowlist.isNotEmpty() && packageName !in senderAllowlist) return false
    return looksLikeOtp(text)
}
