package to.sava.peranta.timeline

/** エラー追記を抑える時間枠。 */
const val ERROR_SUPPRESSION_WINDOW_MILLIS: Long = 60 * 1000L

/**
 * タイムラインへのエラー追記を時間枠ごとに 1 件へ抑える（§10.5）。永続化・OS 通知・トーストは
 * いずれも追記を起点にするため、追記を抑えれば 3 つとも同時に抑えられる。
 *
 * 抑止の単位は [ErrorKind.origin] で決める。外部入力起因（[ErrorOrigin.UNTRUSTED_INPUT]）は
 * 文言が毎回変わっても積み上がらないよう種別ごとに、自端末起因（[ErrorOrigin.LOCAL_OPERATION]）は
 * 別々の失敗がそれぞれ見えるよう文言ごとに抑える。
 *
 * 窓は固定で、枠内の 2 件目以降を落としても次の枠の 1 件目は必ず通す。流量を当て続けられても
 * 「1 件も出ない」状態にはならず、鍵の読み直し忘れ（[ErrorKind.KEY_ID_MISMATCH]）の可視化は保たれる。
 */
class ErrorSuppressor(private val windowMillis: Long = ERROR_SUPPRESSION_WINDOW_MILLIS) {

    private val lastAppendedAt = mutableMapOf<Pair<ErrorKind, String?>, Long>()

    private val suppressedCounts = mutableMapOf<Pair<ErrorKind, String?>, Int>()

    /**
     * [kind] / [message] のエラーを時刻 [at] に追記してよいかを返す。
     * 抑止したときは false を返し、件数を数えて次に通す 1 件へ引き継ぐ。
     */
    fun allows(kind: ErrorKind, message: String, at: Long): Boolean {
        val key = keyFor(kind, message)
        lastAppendedAt.entries.removeAll { at - it.value > windowMillis }
        if (key in lastAppendedAt) {
            suppressedCounts[key] = (suppressedCounts[key] ?: 0) + 1
            return false
        }
        lastAppendedAt[key] = at
        return true
    }

    /** [allows] が true を返した直後に、その 1 件へ畳み込まれた抑止件数を取り出す。 */
    fun takeSuppressedCount(kind: ErrorKind, message: String): Int =
        suppressedCounts.remove(keyFor(kind, message)) ?: 0

    /** 外部入力起因は種別だけを、自端末起因は種別と文言の対をキーにする。 */
    private fun keyFor(kind: ErrorKind, message: String): Pair<ErrorKind, String?> = when (kind.origin) {
        ErrorOrigin.UNTRUSTED_INPUT -> kind to null
        ErrorOrigin.LOCAL_OPERATION -> kind to message
    }
}
