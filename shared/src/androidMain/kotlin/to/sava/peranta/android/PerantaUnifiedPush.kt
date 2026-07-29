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

/** ntfy アプリのパッケージ名。自動で採用するディストリビュータはこれだけとする。 */
private const val NTFY_PACKAGE = "io.heckel.ntfy"

/** ディストリビュータ不在をタイムラインで知らせる文言（§10.5）。 */
private const val NO_DISTRIBUTOR_MESSAGE =
    "UnifiedPush ディストリビュータが見つかりません。ntfy アプリを導入して既定に設定してください"

/** ntfy 以外のディストリビュータしか無いことをタイムラインで知らせる文言（§10.5）。 */
private const val NO_NTFY_DISTRIBUTOR_MESSAGE =
    "UnifiedPush ディストリビュータに ntfy アプリが見つかりません。" +
        "他のディストリビュータは自動では選ばないため、ntfy アプリを導入して既定に設定してください"

/** 登録失敗をタイムラインで知らせる文言（§10.5）。 */
private const val REGISTRATION_FAILED_MESSAGE =
    "UnifiedPush の登録に失敗しました。ntfy アプリの設定を確認してください"

/** ディストリビュータの採否（[distributorSelection] の判定結果）。 */
internal sealed interface DistributorSelection {

    /** 保存済みのディストリビュータが現存するので、それをそのまま使う。 */
    data object KeepSaved : DistributorSelection

    /** [packageName] を採用して保存する。 */
    data class Adopt(val packageName: String) : DistributorSelection

    /** ディストリビュータが 1 つも無い。 */
    data object NoCandidate : DistributorSelection

    /** ntfy が候補に無い。他のディストリビュータは採用しない。 */
    data object NoNtfy : DistributorSelection
}

/**
 * 保存済み（[saved]）と現存する候補（[distributors]）から、採用するディストリビュータを決める。
 * 保存済みが現存すればそれを使い、無ければ ntfy だけを自動で採る。
 *
 * ntfy が候補に無いときは何も採用しない。ディストリビュータは intent-filter を宣言するだけで
 * 名乗れるうえ、Peranta は払い出されたエンドポイントの topic へ**自端末の設定サーバ**を宛先に
 * publish する（`net/KtorNtfyClient`）ので、自分の ntfy サーバを購読しないディストリビュータでは
 * 配信がそもそも成立しない。
 */
internal fun distributorSelection(distributors: List<String>, saved: String?): DistributorSelection =
    when {
        distributors.isEmpty() -> DistributorSelection.NoCandidate
        saved != null && saved in distributors -> DistributorSelection.KeepSaved
        NTFY_PACKAGE in distributors -> DistributorSelection.Adopt(NTFY_PACKAGE)
        else -> DistributorSelection.NoNtfy
    }

/**
 * UnifiedPush 登録の起点（§3.2）。受信設定が揃っているときに、ディストリビュータ（ntfy アプリ）へ
 * 登録要求を出してエンドポイントの払い出しを促す。
 */
object PerantaUnifiedPush {

    private val log = Logger.withTag("UnifiedPush")
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /**
     * 受信ロールが有効なら UnifiedPush に登録する。
     * 採用するディストリビュータは [distributorSelection] が決める。登録できない状態
     * （候補なし・ntfy 以外しか無い）は、ログとタイムラインのエラーで知らせて登録へ進まない。
     */
    fun register(context: Context) {
        val appContext = context.applicationContext
        val config = androidConfigRepository(appContext).load()
        if (!config.isReadyForUnifiedPushReceive) {
            log.i { "receive not configured; skipping unifiedpush registration" }
            return
        }
        val distributors = UnifiedPush.getDistributors(appContext)
        val saved = UnifiedPush.getSavedDistributor(appContext)
        when (val selection = distributorSelection(distributors, saved)) {
            DistributorSelection.NoCandidate -> {
                log.w { "no unifiedpush distributor available; cannot register" }
                reportError(appContext, NO_DISTRIBUTOR_MESSAGE)
                return
            }
            DistributorSelection.NoNtfy -> {
                log.w { "ntfy distributor not available; not adopting another one" }
                reportError(appContext, NO_NTFY_DISTRIBUTOR_MESSAGE)
                return
            }
            is DistributorSelection.Adopt -> {
                log.i { "saving unifiedpush distributor: ${selection.packageName}" }
                UnifiedPush.saveDistributor(appContext, selection.packageName)
            }
            DistributorSelection.KeepSaved -> Unit
        }
        log.i { "registering with unifiedpush distributor" }
        UnifiedPush.register(appContext, messageForDistributor = DISTRIBUTOR_MESSAGE)
    }

    /** 受信ロールを解除する（登録の取り消し）。 */
    fun unregister(context: Context) {
        UnifiedPush.unregister(context.applicationContext)
    }

    /** エンドポイントを取り直す。ntfy アプリの既定サーバー変更後は再登録でしか新サーバーへ移れない。 */
    fun reregister(context: Context) {
        unregister(context)
        register(context)
    }

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
