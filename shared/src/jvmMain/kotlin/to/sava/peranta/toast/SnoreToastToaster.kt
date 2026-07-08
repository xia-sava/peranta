package to.sava.peranta.toast

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import to.sava.peranta.platform.ioDispatcher
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.absolutePathString

/** 同時に表示できる persistent トーストの上限。復号失敗ストーム等でのプロセス枯渇を防ぐ。 */
private const val MAX_CONCURRENT_TOASTS = 8

/**
 * SnoreToast.exe をプロセス起動して Windows トーストを表示する [Toaster]。
 *
 * persistent トーストはユーザー操作まで生存するため、完了待ちは [Process.onExit] のノンブロッキング待機で行い、
 * スレッドを占有しない。キャンセル時は子プロセスを破棄する。同時表示数は [maxConcurrent] で制限する。
 */
class SnoreToastToaster(
    exePath: Path,
    private val log: Logger = Logger.withTag("Toaster"),
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
    maxConcurrent: Int = MAX_CONCURRENT_TOASTS,
) : Toaster {

    private val exe: String = exePath.absolutePathString()
    private val displaySlots = Semaphore(maxConcurrent)

    override suspend fun show(item: ReceivedNotificationToast): ToastResult {
        if (!displaySlots.tryAcquire()) {
            log.w { "toast skipped: concurrent display limit ($MAX_CONCURRENT_TOASTS) reached id=${item.id}" }
            return ToastResult.Failed
        }
        return try {
            val code = runProcess(SnoreToastCommand.showArgs(exe, item))
            SnoreToastCommand.resultFromExitCode(code).also {
                log.i { "toast shown id=${item.id} exit=$code result=$it" }
            }
        } finally {
            displaySlots.release()
        }
    }

    override suspend fun close(id: String) {
        val code = runProcess(SnoreToastCommand.closeArgs(exe, id))
        log.i { "toast close id=$id exit=$code" }
    }

    private suspend fun runProcess(args: List<String>): Int =
        withContext(dispatcher) {
            val process = try {
                ProcessBuilder(args).redirectErrorStream(true).start()
            } catch (e: IOException) {
                log.e(e) { "failed to launch snoretoast: ${args.firstOrNull()}" }
                return@withContext FAILED_EXIT
            }
            try {
                process.onExit().await().exitValue()
            } finally {
                if (process.isAlive) {
                    process.destroy()
                }
            }
        }

    private companion object {
        /** [SnoreToastCommand.resultFromExitCode] が [ToastResult.Failed] に落とす番兵値。 */
        const val FAILED_EXIT = Int.MIN_VALUE
    }
}
