package to.sava.peranta.android

import android.content.Context
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.UnifiedPush
import to.sava.peranta.platform.ioDispatcher

/** ディストリビュータ UI に表示され、この登録を識別する文言。 */
private const val DISTRIBUTOR_MESSAGE = "Peranta"

/** ntfy アプリのパッケージ名。複数ディストリビュータがあるときに優先して選ぶ。 */
private const val NTFY_PACKAGE = "io.heckel.ntfy"

/** ディストリビュータ不在をタイムラインで知らせる文言（§10.5）。 */
private const val NO_DISTRIBUTOR_MESSAGE =
    "UnifiedPush ディストリビュータが見つかりません。ntfy アプリを導入して既定に設定してください"

/** 登録失敗をタイムラインで知らせる文言（§10.5）。 */
private const val REGISTRATION_FAILED_MESSAGE =
    "UnifiedPush の登録に失敗しました。ntfy アプリの設定を確認してください"

/**
 * UnifiedPush 登録の起点（§3.2）。受信設定が揃っているときに、ディストリビュータ（ntfy アプリ）へ
 * 登録要求を出してエンドポイントの払い出しを促す。
 */
object PerantaUnifiedPush {

    private val log = Logger.withTag("UnifiedPush")
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /**
     * 受信ロールが有効なら UnifiedPush に登録する。
     * ディストリビュータが 1 つも無い場合は登録できないため、ログとタイムラインのエラーで知らせる。
     * 保存済みディストリビュータが現存しない（アンインストール等）ときは決定的に選び直す。
     */
    fun register(context: Context) {
        val appContext = context.applicationContext
        val config = androidConfigRepository(appContext).load()
        if (!config.isReadyForUnifiedPushReceive) {
            log.i { "receive not configured; skipping unifiedpush registration" }
            return
        }
        val distributors = UnifiedPush.getDistributors(appContext)
        if (distributors.isEmpty()) {
            log.w { "no unifiedpush distributor available; cannot register" }
            reportError(appContext, NO_DISTRIBUTOR_MESSAGE)
            return
        }
        val saved = UnifiedPush.getSavedDistributor(appContext)
        if (saved == null || saved !in distributors) {
            val chosen = chooseDistributor(distributors)
            log.i { "saving unifiedpush distributor: $chosen" }
            UnifiedPush.saveDistributor(appContext, chosen)
        }
        log.i { "registering with unifiedpush distributor" }
        UnifiedPush.register(appContext, messageForDistributor = DISTRIBUTOR_MESSAGE)
    }

    /** 受信ロールを解除する（登録の取り消し）。 */
    fun unregister(context: Context) {
        UnifiedPush.unregister(context.applicationContext)
    }

    /** ntfy を優先し、無ければ先頭を選ぶ。同じ端末構成なら常に同じ選択になる。 */
    private fun chooseDistributor(distributors: List<String>): String =
        distributors.firstOrNull { it == NTFY_PACKAGE } ?: distributors.first()

    /** 登録失敗をタイムラインへ反映する。ディストリビュータ不在の文言と整合させる。 */
    internal fun reportRegistrationFailed(context: Context) {
        reportError(context.applicationContext, REGISTRATION_FAILED_MESSAGE)
    }

    private fun reportError(context: Context, message: String) {
        scope.launch {
            try {
                PerantaReceive.reportError(context, message)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                log.w(error) { "failed to record unifiedpush error" }
            }
        }
    }
}
