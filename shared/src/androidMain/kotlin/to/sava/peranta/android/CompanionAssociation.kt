package to.sava.peranta.android

import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService

/**
 * コンパニオン機器としての登録。
 * Android 15 以降は、機微と判定された通知の本文が「信頼されていない」通知リスナーへ渡らず、
 * 代わりに公開版（本文が伏せられたもの）が届く。コンパニオン機器の登録があるアプリの
 * リスナーは信頼済みとして扱われ、本文をそのまま受け取れる。
 * 登録は機器の種別を問わず 1 件あれば足りるため、種別フィルタもプロファイルも指定しない
 * （プロファイル指定には特権が要る）。
 */
object CompanionAssociation {

    /**
     * この端末で登録が要るか。伏せ字化は Android 15 以降の挙動なので、それ未満では登録しても
     * 通知の本文には効かない。
     */
    fun isRequired(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    /** 登録済みか。1 件でもあれば信頼済みとして扱われる。 */
    fun isAssociated(context: Context): Boolean =
        manager(context)?.myAssociations?.isNotEmpty() ?: false

    /** 登録を求めるリクエスト。一覧から選ばせるだけなので条件は付けない。 */
    fun request(): AssociationRequest = AssociationRequest.Builder().build()

    /**
     * [CompanionDeviceManager]。登録一覧の取得も登録の要求も Android 13 以降の API を使うため、
     * それ未満では扱わない（伏せ字化自体が Android 15 以降なので実害はない）。
     */
    fun manager(context: Context): CompanionDeviceManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(CompanionDeviceManager::class.java)
        } else {
            null
        }

    /**
     * 通知リスナーを張り直す。信頼済みかの判定はバインド時に効くため、登録した直後に
     * 呼ばないと、次に OS がバインドし直すまで伏せ字のままになる。
     */
    fun rebindNotificationListener(context: Context) {
        NotificationListenerService.requestRebind(
            ComponentName(context.applicationContext, PerantaNotificationListenerService::class.java),
        )
    }
}
