package to.sava.peranta.android

import android.content.Context
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * UnifiedPush のブロードキャストを受ける受信口（§3.2）。
 * ディストリビュータ（ntfy アプリ）が Doze 中でもこの receiver を起こし、
 * 払い出しエンドポイントの通知と到達メッセージ（暗号文 Envelope）を届ける。
 */
class PerantaUnifiedPushReceiver : MessagingReceiver() {

    private val log = Logger.withTag("UnifiedPushRecv")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 払い出されたエンドポイント URL を設定へ保存する（§8）。
     * これが自分の受信 topic であり、presence での配布（M7）はここを起点にする。
     */
    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        val appContext = context.applicationContext
        log.i { "new endpoint received (instance=$instance)" }
        val repo = androidConfigRepository(appContext)
        repo.save(repo.load().copy(unifiedPushEndpoint = endpoint.url))
        val pendingResult = goAsync()
        scope.launch {
            try {
                announcePresence(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * 到達メッセージ（暗号文 Envelope）を受信中核へ渡す。
     * 復号成功なら [PerantaReceive] が OS 通知を表示し、失敗ならエラーとして記録する。
     */
    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        val appContext = context.applicationContext
        val rawMessage = message.content.decodeToString()
        if (PerantaSelfTest.consumeMarker(rawMessage)) return
        val pendingResult = goAsync()
        scope.launch {
            try {
                PerantaReceive.handleEnvelopeCatching(appContext, rawMessage)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {
        log.w { "unifiedpush registration failed: reason=$reason instance=$instance" }
        PerantaUnifiedPush.reportRegistrationFailed(context)
    }

    override fun onUnregistered(context: Context, instance: String) {
        log.i { "unifiedpush unregistered (instance=$instance)" }
        val appContext = context.applicationContext
        val repo = androidConfigRepository(appContext)
        repo.save(repo.load().copy(unifiedPushEndpoint = null))
    }
}
