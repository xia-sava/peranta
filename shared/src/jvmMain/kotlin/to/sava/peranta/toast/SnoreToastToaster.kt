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
            val outcome = runProcess(SnoreToastCommand.showArgs(exe, item))
            val result = if (item.openUrl != null) {
                SnoreToastCommand.resultFrom(outcome.exitCode, outcome.stdout)
            } else {
                SnoreToastCommand.resultFromExitCode(outcome.exitCode)
            }
            result.also {
                log.i { "toast shown id=${item.id} exit=${outcome.exitCode} result=$it" }
            }
        } finally {
            displaySlots.release()
        }
    }

    override suspend fun close(id: String) {
        val outcome = runProcess(SnoreToastCommand.closeArgs(exe, id))
        log.i { "toast close id=$id exit=${outcome.exitCode}" }
    }

    private suspend fun runProcess(args: List<String>): ProcessOutcome =
        withContext(dispatcher) {
            val process = try {
                ProcessBuilder(args).redirectErrorStream(true).start()
            } catch (e: IOException) {
                log.e(e) { "failed to launch snoretoast: ${args.firstOrNull()}" }
                return@withContext ProcessOutcome(FAILED_EXIT, "")
            }
            try {
                val exitCode = process.onExit().await().exitValue()
                val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
                ProcessOutcome(exitCode, stdout)
            } finally {
                if (process.isAlive) {
                    process.destroy()
                }
            }
        }

    /** [runProcess] の結果。ボタン名判別（§3.3）に使う [stdout] は exit code 4 のときだけ意味を持つ。 */
    private data class ProcessOutcome(val exitCode: Int, val stdout: String)

    private companion object {
        /** [SnoreToastCommand.resultFromExitCode] が [ToastResult.Failed] に落とす番兵値。 */
        const val FAILED_EXIT = Int.MIN_VALUE
    }
}
