package to.sava.peranta.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import to.sava.peranta.platform.RecordingLogWriter
import to.sava.peranta.platform.recordingLogger
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ダウンロード済みとして扱う APK の中身。 */
private const val APK_CONTENT = "peranta-apk"

/** APK と一致しない sha256。 */
private val MISMATCHED_DIGEST = "0".repeat(64)

/**
 * Android の更新の進行（[downloadAndHandOffApk] と [handOffVerifiedApk]）を固定する（§12）。
 * sha256 → packageName → インストーラへの引き渡し、の順を守ること、照合に落ちた APK は破棄して
 * 引き渡さないこと、引き渡しの失敗を照合の失敗と分けることを確かめる。
 */
class AndroidUpdaterTest {

    private val dir: File = Files.createTempDirectory("peranta-android-update-test").toFile()
    private val apk: File = File(dir, "peranta-update.apk")

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    /** 1 回の進行で呼ばれたものを記録する。 */
    private class Recorder {
        val states = mutableListOf<UpdateInstallState>()
        val discarded = mutableListOf<File>()
        val packageChecked = mutableListOf<File>()
        val launched = mutableListOf<File>()
    }

    private fun handOff(
        recorder: Recorder,
        verifyPackage: (File) -> Boolean,
        launch: (File) -> Unit = { recorder.launched += it },
    ) = handOffVerifiedApk(
        apk = apk,
        verifyPackage = { file ->
            recorder.packageChecked += file
            verifyPackage(file)
        },
        launch = launch,
        discard = { recorder.discarded += it },
        onState = { recorder.states += it },
        log = recordingLogger(RecordingLogWriter(), "Updater"),
    )

    private fun available(sha256: String) =
        UpdateStatus.Available("2.0.0", "https://example.com/peranta.apk", sha256)

    /** [APK_CONTENT] を [apk] へ書いて返す取得で、[downloadAndHandOffApk] を走らせる。 */
    private suspend fun install(recorder: Recorder, sha256: String, verifyPackage: (File) -> Boolean = { true }) =
        downloadAndHandOffApk(
            available = available(sha256),
            download = { _, onProgress ->
                apk.writeText(APK_CONTENT)
                onProgress(apk.length(), apk.length())
                apk
            },
            verifyPackage = { file ->
                recorder.packageChecked += file
                verifyPackage(file)
            },
            launch = { recorder.launched += it },
            discard = { file ->
                recorder.discarded += file
                file.delete()
            },
            onState = { recorder.states += it },
            log = recordingLogger(RecordingLogWriter(), "Updater"),
        )

    private fun digestOf(content: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(content.encodeToByteArray())
            .joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /** 自アプリの APK はインストーラへ引き渡され、破棄されずに引き渡し済みで止まる。 */
    @Test
    fun verifiedPackageIsHandedOff() {
        val recorder = Recorder()

        handOff(recorder, verifyPackage = { true })

        assertEquals(listOf(apk), recorder.launched)
        assertTrue(recorder.discarded.isEmpty())
        assertEquals(listOf<UpdateInstallState>(UpdateInstallState.Launching), recorder.states)
    }

    /**
     * packageName の照合が偽を返した APK も、照合の途中で例外になった APK も、破棄され、
     * 引き渡さずに照合の失敗で止まる。
     */
    @Test
    fun unverifiedPackageIsDiscardedWithoutHandingOff() {
        mapOf<String, (File) -> Boolean>(
            "mismatch" to { false },
            "check error" to { throw RuntimeException("unparseable apk") },
        ).forEach { (case, verifyPackage) ->
            val recorder = Recorder()

            handOff(recorder, verifyPackage)

            assertTrue(recorder.launched.isEmpty(), "$case: rejected apk reached the installer")
            assertEquals(listOf(apk), recorder.discarded, case)
            assertEquals(
                listOf<UpdateInstallState>(UpdateInstallState.Failed(UPDATE_VERIFY_FAILED_MESSAGE)),
                recorder.states,
                case,
            )
        }
    }

    /** インストーラを起動できなければ、照合の失敗とは別の適用の失敗で止まり、APK は破棄しない。 */
    @Test
    fun launchFailureReportsApplyFailureAndKeepsApk() {
        val recorder = Recorder()

        handOff(recorder, verifyPackage = { true }, launch = { throw IllegalStateException("no installer") })

        assertTrue(recorder.discarded.isEmpty(), "apk was discarded after a launch failure")
        assertEquals(
            listOf<UpdateInstallState>(UpdateInstallState.Failed(UPDATE_APPLY_FAILED_MESSAGE)),
            recorder.states,
        )
    }

    /** packageName の照合中のキャンセルは握られずに伝わり、引き渡しへ進まず、失敗の状態も出ない。 */
    @Test
    fun cancellationDuringPackageCheckPropagates() {
        val recorder = Recorder()

        assertFailsWith<CancellationException> {
            handOff(recorder, verifyPackage = { throw CancellationException("cancelled") })
        }

        assertTrue(recorder.launched.isEmpty())
        assertTrue(recorder.states.isEmpty())
    }

    /** sha256 が食い違う APK は、packageName の照合にも起動にも渡されずに破棄され、照合の失敗で止まる。 */
    @Test
    fun digestMismatchSkipsPackageCheckAndLaunch() = runTest {
        val recorder = Recorder()

        install(recorder, MISMATCHED_DIGEST)

        assertTrue(recorder.packageChecked.isEmpty(), "unverified apk reached the package check")
        assertTrue(recorder.launched.isEmpty(), "unverified apk reached the installer")
        assertEquals(listOf(apk), recorder.discarded)
        assertFalse(apk.exists(), "rejected apk was left on disk")
        assertEquals(UpdateInstallState.Failed(UPDATE_VERIFY_FAILED_MESSAGE), recorder.states.last())
    }

    /** sha256 と packageName の照合を通った APK は引き渡され、取得・照合中・引き渡し済みの順に進む。 */
    @Test
    fun allChecksPassReachLaunching() = runTest {
        val recorder = Recorder()

        install(recorder, digestOf(APK_CONTENT))

        assertEquals(listOf(apk), recorder.packageChecked)
        assertEquals(listOf(apk), recorder.launched)
        assertTrue(recorder.discarded.isEmpty())
        val length = APK_CONTENT.length.toLong()
        assertEquals(
            listOf(
                UpdateInstallState.Downloading(length, length),
                UpdateInstallState.Verifying,
                UpdateInstallState.Launching,
            ),
            recorder.states,
        )
    }
}
