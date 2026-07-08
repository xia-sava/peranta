package to.sava.peranta

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import to.sava.peranta.android.PerantaReceive

/**
 * debug ビルド限定の受信テスト経路（§16）。UnifiedPush の onMessage 相当を再現するため、
 * adb `am broadcast -a to.sava.peranta.DEBUG_ENVELOPE --es envelope '<Envelope JSON>'` から
 * 暗号文 Envelope を流し込み、復号 → 通知表示までを確認する。
 */
class DebugEnvelopeReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val envelope = intent.getStringExtra(EXTRA_ENVELOPE) ?: run {
            resultData = "missing 'envelope' extra"
            return
        }
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        scope.launch {
            try {
                PerantaReceive.handleEnvelopeCatching(appContext, envelope)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION = "to.sava.peranta.DEBUG_ENVELOPE"
        const val EXTRA_ENVELOPE = "envelope"
    }
}
