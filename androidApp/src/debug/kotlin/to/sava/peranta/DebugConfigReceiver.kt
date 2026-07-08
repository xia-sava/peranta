package to.sava.peranta

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import to.sava.peranta.android.androidConfigRepository
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.filter.FilterMode
import to.sava.peranta.filter.FilterRule
import to.sava.peranta.filter.RuleAction

/**
 * debug ビルド限定の設定注入口（§16）。adb `am broadcast -a to.sava.peranta.DEBUG_CONFIG` から
 * 設定を書き込み、反映後の主要値を result data として echo する。
 */
class DebugConfigReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val repo = androidConfigRepository(context.applicationContext)
        val current = repo.load()
        val updated = current.applyExtras(intent)
        repo.save(updated)
        resultData = echo(updated)
    }

    private fun PerantaConfig.applyExtras(intent: Intent): PerantaConfig = copy(
        host = intent.getStringExtra("host") ?: host,
        port = if (intent.hasExtra("port")) intent.getIntExtra("port", 0) else port,
        useTls = intent.getStringExtra("tls")?.toBooleanStrictOrNull() ?: useTls,
        accessToken = intent.getStringExtra("token") ?: accessToken,
        sharedKeyBase64 = intent.getStringExtra("keyB64") ?: sharedKeyBase64,
        keyId = intent.getStringExtra("keyId") ?: keyId,
        deviceName = intent.getStringExtra("device") ?: deviceName,
        sendEnabled = intent.getStringExtra("sendEnabled")?.toBooleanStrictOrNull() ?: sendEnabled,
        smsDirectReceive = intent.getStringExtra("smsEnabled")?.toBooleanStrictOrNull() ?: smsDirectReceive,
        filterMode = intent.getStringExtra("filterMode")
            ?.let { name -> FilterMode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
            ?: filterMode,
        deliveryTopics = intent.getStringExtra("deliveryTopics")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: deliveryTopics,
        persistSensitiveHistory = intent.getStringExtra("persistSensitive")?.toBooleanStrictOrNull()
            ?: persistSensitiveHistory,
        otpSenderPackages = intent.getStringExtra("otpSenders")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: otpSenderPackages,
        filterRules = mergeRules(filterRules, intent),
    )

    /**
     * includePackages / excludePackages（カンマ区切り）を既存ルールへ反映する。
     * 同一パッケージの既存ルールは新しい action で上書きする。
     */
    private fun mergeRules(existing: List<FilterRule>, intent: Intent): List<FilterRule> {
        val base = if (intent.getStringExtra("clearRules")?.toBooleanStrictOrNull() == true) {
            emptyList()
        } else {
            existing
        }
        val includes = intent.packageList("includePackages").map { FilterRule(it, RuleAction.INCLUDE) }
        val excludes = intent.packageList("excludePackages").map { FilterRule(it, RuleAction.EXCLUDE) }
        val overrides = includes + excludes
        if (overrides.isEmpty()) return base
        val overridden = overrides.map { it.packageName }.toSet()
        return base.filterNot { it.packageName in overridden } + overrides
    }

    private fun Intent.packageList(key: String): List<String> =
        getStringExtra(key)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private fun echo(config: PerantaConfig): String = buildString {
        append("host=").append(config.host)
        append(" port=").append(config.port ?: "default")
        append(" tls=").append(config.useTls)
        append(" device=").append(config.deviceName ?: "-")
        append(" keyId=").append(config.keyId ?: "-")
        append(" hasKey=").append(config.sharedKeyBase64 != null)
        append(" hasToken=").append(config.accessToken != null)
        append(" sendEnabled=").append(config.sendEnabled)
        append(" smsEnabled=").append(config.smsDirectReceive)
        append(" filterMode=").append(config.filterMode.name)
        append(" deliveryTopics=").append(config.deliveryTopics.joinToString(","))
        append(" persistSensitive=").append(config.persistSensitiveHistory)
        append(" otpSenders=").append(config.otpSenderPackages.joinToString(","))
        append(" filterRules=").append(config.filterRules.size)
    }

    companion object {
        const val ACTION = "to.sava.peranta.DEBUG_CONFIG"
    }
}
