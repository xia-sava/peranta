package to.sava.peranta.receive

import to.sava.peranta.model.notificationKeyOrNull
import to.sava.peranta.timeline.SentNotification
import to.sava.peranta.timeline.TimelineItem
import kotlin.jvm.JvmInline

/**
 * 自端末が転送した通知の notificationKey であることを検査済みの値（§3.4）。
 *
 * 他アプリの通知を操作する実行系（[NotificationOps]）は対象をこの型でしか受け取らないため、
 * 検査を経ていない key で通知操作を組み立てられない。発行経路は [Companion.authorize] だけとする。
 */
@JvmInline
value class AuthorizedNotificationKey private constructor(val raw: String) {

    companion object {
        /**
         * [notificationKey] を送信済みタイムライン [items] と照合し、自端末の転送実績があれば認可する。
         * 照合先は自端末が転送した通知（[SentNotification]）だけで、他端末から受信した通知は含めない。
         * 実績が無ければ null を返す。
         *
         * タイムラインは永続するため、判定はプロセス再起動をまたいで成立する。
         * 剪定（§10.1）でタイムラインから落ちた古い通知は認可の対象から外れる。
         */
        fun authorize(items: List<TimelineItem>, notificationKey: String): AuthorizedNotificationKey? {
            val forwarded = items.asSequence()
                .filterIsInstance<SentNotification>()
                .any { it.payload.notificationKeyOrNull() == notificationKey }
            return if (forwarded) AuthorizedNotificationKey(notificationKey) else null
        }
    }
}

/**
 * 自端末が転送していない通知への操作を拒んだことを表す例外（§3.4）。
 * 実行そのものの失敗（[CommandExecutionException]）とは別種で、タイムラインにも別のエラーとして残す。
 * 実行系の寛容さ（対象が既に存在しない dismiss を致命的にしない等）と混ざらないよう区別する。
 */
class CommandUnauthorizedException(
    message: String,
) : Exception(message)
