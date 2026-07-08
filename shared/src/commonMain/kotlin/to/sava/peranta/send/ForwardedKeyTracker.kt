package to.sava.peranta.send

/** 転送済み notificationKey を覚えておく既定の上限。受信側の重複排除上限と揃える。 */
const val FORWARDED_KEY_CAPACITY: Int = 1000

/**
 * 自端末が転送した通知の notificationKey を短期記憶する（§3.4）。
 * 元通知が消えたとき（[PerantaNotificationListenerService.onNotificationRemoved]）に、
 * その削除が「自分が転送した通知の削除」かどうかを判定して既読同期の dismiss を送るかを決める。
 * 他アプリの無関係な通知削除まで拾わないための絞り込みに使う。
 * 単一スレッド（NLS のコールバック）での利用を前提とする。
 */
class ForwardedKeyTracker(private val capacity: Int = FORWARDED_KEY_CAPACITY) {

    private val keys = LinkedHashSet<String>()

    /** [key] を転送済みとして記録する。上限を超えた分は最古から淘汰する。 */
    fun remember(key: String) {
        if (keys.add(key) && keys.size > capacity) {
            keys.remove(keys.iterator().next())
        }
    }

    /**
     * [key] が転送済みなら true を返し、記録から取り除く。
     * 削除検知は一度きりのため、判定と同時に消費する。
     */
    fun consume(key: String): Boolean = keys.remove(key)
}
