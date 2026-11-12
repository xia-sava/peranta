package to.sava.peranta.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 適用の多重起動を防ぐ判定（[isInProgress]）が、状態ごとに進行中かどうかを正しく返すことを固定する（§12）。 */
class UpdateInstallInProgressTest {

    /** ダウンロード中・照合中・適用の確認待ち・インストーラへ引き渡し済みは進行中とする。 */
    @Test
    fun runningStatesAreInProgress() {
        listOf(
            UpdateInstallState.Downloading(0, 0),
            UpdateInstallState.Downloading(512, 1_024),
            UpdateInstallState.Verifying,
            UpdateInstallState.ReadyToApply,
            UpdateInstallState.Launching,
        ).forEach { state ->
            assertTrue(state.isInProgress(), "$state should be in progress")
        }
    }

    /** 未着手（null）と失敗は進行中ではなく、適用をもう一度始められる。 */
    @Test
    fun idleAndFailedAreNotInProgress() {
        listOf(
            null,
            UpdateInstallState.Failed(UPDATE_DOWNLOAD_FAILED_MESSAGE),
            UpdateInstallState.Failed(UPDATE_VERIFY_FAILED_MESSAGE),
            UpdateInstallState.Failed(UPDATE_APPLY_FAILED_MESSAGE),
        ).forEach { state ->
            assertFalse(state.isInProgress(), "$state should not be in progress")
        }
    }
}
