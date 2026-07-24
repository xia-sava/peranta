package to.sava.peranta.android

/**
 * ミラー通知タップでアプリへ渡す、タイムライン上の対象アイテム id を格納する Intent extra キー
 * （§3.2）。通知表示時の PendingIntent 組み立てと、MainActivity での起動 Intent / onNewIntent
 * 双方の解釈で共有する。
 */
const val EXTRA_SCROLL_ITEM_ID = "to.sava.peranta.EXTRA_SCROLL_ITEM_ID"

/**
 * Intent extra から取り出した対象アイテム id を正規化する。MainActivity は exported な
 * Activity であり、他アプリが明示コンポーネント指定で任意の extra を積んで起動できるため、
 * 空文字・空白のみの値は「対象なし」として null に丸める。
 */
fun normalizeScrollItemId(raw: String?): String? = raw?.takeIf { it.isNotBlank() }
