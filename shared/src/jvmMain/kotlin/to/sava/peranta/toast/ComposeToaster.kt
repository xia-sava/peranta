package to.sava.peranta.toast

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CompletableDeferred

/** 同時に表示できるトーストの上限。受信が続いても画面を埋め尽くさないようにする。 */
private const val MAX_CONCURRENT_TOASTS = 8

/** トースト表示に合わせて鳴らす音。音を出さない環境やテストでは [ToastSound.Silent]。 */
fun interface ToastSound {

    fun play()

    companion object {
        val Silent: ToastSound = ToastSound {}
    }
}

/** 表示中の 1 トースト。ユーザー操作か取り下げで [finish] され、それが [Toaster.show] の戻り値になる。 */
class ActiveToast internal constructor(item: ReceivedNotificationToast) {

    /** 表示内容。後から届いた画像の差し込み（§4.3.1）で表示中に差し替わる。 */
    var item: ReceivedNotificationToast by mutableStateOf(item)
        internal set

    private val outcome = CompletableDeferred<ToastResult>()

    /** 表示を終える。最初の 1 回だけが結果になる。 */
    fun finish(result: ToastResult) {
        outcome.complete(result)
    }

    internal suspend fun await(): ToastResult = outcome.await()
}

/**
 * Compose のウィンドウでトーストを描く [Toaster]。[active] に積んだ表示要求を UI 側が描き、
 * [ActiveToast.finish] で結果が戻るまで [show] はサスペンドする。
 */
class ComposeToaster(
    private val sound: ToastSound = ToastSound.Silent,
    private val log: Logger = Logger.withTag("Toaster"),
    private val maxConcurrent: Int = MAX_CONCURRENT_TOASTS,
) : Toaster {

    /**
     * 表示中のトースト。古い順に並ぶ。
     * UI スレッドのほか、表示・差し替え・取り下げを求める各スレッドから並行して触られる。走査は
     * [SnapshotStateList.toList] で得た変わらない一覧に対して行う。並行する追加・取り除きがあると、
     * この一覧を直に走査する途中で [ConcurrentModificationException] が投げられるため。
     */
    val active: SnapshotStateList<ActiveToast> = mutableStateListOf()

    override suspend fun show(item: ReceivedNotificationToast): ToastResult {
        if (active.size >= maxConcurrent) {
            log.w { "toast skipped: concurrent display limit ($maxConcurrent) reached id=${item.id}" }
            return ToastResult.Failed
        }
        val toast = ActiveToast(item)
        active.add(toast)
        sound.play()
        return try {
            toast.await().also { log.i { "toast shown id=${item.id} result=$it" } }
        } finally {
            active.remove(toast)
        }
    }

    /**
     * 同じ通知の再投稿で同一 id が複数並ぶことがあるため、一致するものを全件取り下げる。
     * 表示中でない id の取り下げは何もせず、記録も残さない（取り下げの要求は表示の有無を問わず届く）。
     */
    override suspend fun close(id: String) {
        val targets = active.toList().filter { it.item.id == id }
        if (targets.isEmpty()) return
        targets.forEach { it.finish(ToastResult.Closed) }
        log.i { "toast close id=$id count=${targets.size}" }
    }

    override suspend fun update(item: ReceivedNotificationToast) {
        val targets = active.toList().filter { it.item.id == item.id }
        if (targets.isEmpty()) return
        targets.forEach { it.item = item }
        log.i { "toast updated id=${item.id}" }
    }
}
