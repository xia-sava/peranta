package to.sava.peranta.ui

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.filter.FilterMode
import to.sava.peranta.filter.FilterRule
import to.sava.peranta.filter.setPackageChecked
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineItem

/**
 * インストール済みアプリ 1 件（§10.4 送信ロールの一覧）。
 * アイコンは表示サイズ相当に縮小した [ImageBitmap] をプラットフォーム側で用意し、取得できなければ null。
 * [isSystemApp] は実転送エンジンと同じシステムアプリ判定（ランチャー有無）に基づく。
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
    val icon: ImageBitmap? = null,
)

/**
 * 送信ロール端末のインストール済みアプリ一覧を供給するスロット（§10.4）。
 * PackageManager アクセスはプラットフォーム（androidMain）へ隔離し、commonMain の画面は結果だけ受け取る。
 * 呼び出しは IO を伴うため suspend とし、ラベルの照合順（日本語対応）まで含めて整えた一覧を返す。
 */
interface InstalledAppsProvider {
    suspend fun loadInstalledApps(): List<InstalledApp>
}

/**
 * 受信専用端末のアプリフィルタ候補（§10.4）。インストール済み一覧が取れないため、
 * タイムライン履歴に現れたパッケージだけを選択肢にする。[senderDeviceId] は mute/unmute の宛先。
 */
data class HistoryPackage(
    val packageName: String,
    val appName: String,
    val senderDeviceId: String,
)

/**
 * タイムライン履歴から、受信専用端末のフィルタ候補（§10.4）を組み立てる。
 * 受信通知に現れたパッケージを重複なく集め、同一パッケージは最後に届いた送信元・表示名で代表させる。
 * 表示名（日本語を含む）で並べる。
 */
fun historyPackagesFrom(items: List<TimelineItem>): List<HistoryPackage> {
    val byPackage = LinkedHashMap<String, HistoryPackage>()
    items.forEach { item ->
        val payload = (item as? ReceivedNotification)?.payload as? NotificationPayload ?: return@forEach
        byPackage[payload.packageName] = HistoryPackage(
            packageName = payload.packageName,
            appName = payload.appName,
            senderDeviceId = payload.from,
        )
    }
    return byPackage.values.sortedBy { it.appName.lowercase() }
}

/**
 * アプリフィルタ画面（§10.4）の永続化と、受信専用端末での送信元への mute/unmute 通知を担う。
 * ルール更新は [ConfigRepository.updateFilterRules] の排他更新に委ね、通知捕捉側の並行書き込みと競合しない。
 * [sendMuteCommand] と [commandScope] が揃うと、ローカルミラー反映と同時に送信元へコマンドを送る。
 */
class AppFilterController(
    private val repository: ConfigRepository,
    private val commandScope: CoroutineScope? = null,
    private val sendMuteCommand: (suspend (packageName: String, senderDeviceId: String, mute: Boolean) -> Unit)? = null,
) {

    /** 現在の設定（フィルタモード・ルール）を読み出す。 */
    fun load(): PerantaConfig = repository.load()

    /** フィルタルールだけを排他更新し、更新後の一覧を返す。 */
    fun updateRules(transform: (List<FilterRule>) -> List<FilterRule>): List<FilterRule> =
        runBlocking { repository.updateFilterRules(transform) }

    /**
     * 受信専用端末で [packageName] の mute/unmute をローカルミラーへ反映し、送信元へも通知する（§10.4-1）。
     * ミラーは denylist の除外として扱い、チェック（[mute]）で除外・解除で復帰する。更新後の一覧を返す。
     */
    fun setMirroredMute(packageName: String, senderDeviceId: String, mute: Boolean): List<FilterRule> {
        val updated = updateRules { rules ->
            setPackageChecked(rules, packageName, checked = mute, FilterMode.DENYLIST, isSystemPackage = false)
        }
        sendMuteCommand?.let { sender ->
            commandScope?.launch { sender(packageName, senderDeviceId, mute) }
        }
        return updated
    }
}
