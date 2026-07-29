package to.sava.peranta.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 状態の待ち受けを打ち切る上限（ミリ秒）。 */
private const val AWAIT_TIMEOUT_MILLIS = 5_000L

/** ダウンロード済みとして扱う配布物の中身。照合に使うダイジェストはこの内容から求める。 */
private const val RELEASE_CONTENT = "peranta-release"

/** 配布物と一致しない sha256。 */
private val MISMATCHED_DIGEST = "0".repeat(64)

/**
 * 照合に失敗した配布物は削除して適用へ進まない、一致した配布物は確認を挟んでから適用へ進む、
 * という Desktop 更新の配線を固定する（§12）。
 */
class DesktopUpdaterTest {

    private fun downloadedRelease(): File =
        File.createTempFile("peranta-update", ".msi").apply {
            deleteOnExit()
            writeText(RELEASE_CONTENT)
        }

    private fun updater(release: File, launched: MutableList<File>) = DesktopUpdater(
        currentVersionCode = 1,
        downloadRelease = { _, onProgress ->
            onProgress(release.length(), release.length())
            release
        },
        launchInstaller = { launched += it },
    )

    private fun available(sha256: String) =
        UpdateStatus.Available("2.0.0", "https://example.com/peranta.msi", sha256)

    /** 照合に失敗した配布物は削除され、ReadyToApply を経ずに Failed で止まり、適用も走らない。 */
    @Test
    fun mismatchedDigestDiscardsReleaseWithoutApplying() = runBlocking {
        val release = downloadedRelease()
        val launched = mutableListOf<File>()
        val updater = updater(release, launched)
        try {
            val observed = mutableListOf<UpdateInstallState?>()
            val watcher = launch(Dispatchers.Default) { updater.installState.collect { observed += it } }

            updater.install(available(MISMATCHED_DIGEST))
            withTimeout(AWAIT_TIMEOUT_MILLIS) {
                updater.installState.first { it is UpdateInstallState.Failed }
            }
            watcher.cancel()

            assertFalse(release.exists(), "rejected release was left on disk")
            assertFalse(observed.contains(UpdateInstallState.ReadyToApply), "rejected release reached apply")

            val exitRequested = AtomicBoolean(false)
            updater.applyNow { exitRequested.set(true) }

            assertFalse(exitRequested.get())
            assertTrue(launched.isEmpty())
            assertTrue(updater.installState.value is UpdateInstallState.Failed)
        } finally {
            updater.close()
        }
    }

    /** 適用の確認を待つあいだに中身が入れ替わった配布物は、引き渡す直前の再照合で弾く。 */
    @Test
    fun tamperedReleaseIsRejectedBeforeApplying() = runBlocking {
        val release = downloadedRelease()
        val launched = mutableListOf<File>()
        val updater = updater(release, launched)
        try {
            updater.install(available(sha256HexOf(release)))
            withTimeout(AWAIT_TIMEOUT_MILLIS) {
                updater.installState.first { it == UpdateInstallState.ReadyToApply }
            }

            release.writeText("tampered-$RELEASE_CONTENT")
            val exitRequested = AtomicBoolean(false)
            updater.applyNow { exitRequested.set(true) }

            assertTrue(launched.isEmpty(), "tampered release reached the installer")
            assertFalse(exitRequested.get())
            assertFalse(release.exists(), "tampered release was left on disk")
            assertTrue(updater.installState.value is UpdateInstallState.Failed)
        } finally {
            updater.close()
        }
    }

    /** 照合に成功した配布物は ReadyToApply で止まり、applyNow で初めてインストーラへ引き渡される。 */
    @Test
    fun matchingDigestWaitsForApplyRequest() = runBlocking {
        val release = downloadedRelease()
        val launched = mutableListOf<File>()
        val updater = updater(release, launched)
        try {
            updater.install(available(sha256HexOf(release)))
            withTimeout(AWAIT_TIMEOUT_MILLIS) {
                updater.installState.first { it == UpdateInstallState.ReadyToApply }
            }

            assertTrue(release.exists())
            assertTrue(launched.isEmpty(), "release was applied before the apply request")

            val exitRequested = AtomicBoolean(false)
            updater.applyNow { exitRequested.set(true) }

            assertEquals(listOf(release), launched)
            assertTrue(exitRequested.get())
            assertEquals(UpdateInstallState.Launching, updater.installState.value)
        } finally {
            updater.close()
        }
    }
}
