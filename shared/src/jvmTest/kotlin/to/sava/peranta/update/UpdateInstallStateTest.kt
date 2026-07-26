package to.sava.peranta.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateInstallStateTest {

    /** 受信量と全体長から 0.0〜1.0 の進み具合を出す。 */
    @Test
    fun fractionFromReceivedAndTotal() {
        assertEquals(0.25f, UpdateInstallState.Downloading(25, 100).fraction)
    }

    /** 全体長が判らなければ進み具合も出せない（長さの決まらない表示に落とす）。 */
    @Test
    fun fractionIsNullWhenTotalUnknown() {
        assertNull(UpdateInstallState.Downloading(25, 0).fraction)
    }

    /** 全体長を超える受信量が来ても 1.0 を上回らない。 */
    @Test
    fun fractionIsClampedToOne() {
        assertEquals(1f, UpdateInstallState.Downloading(120, 100).fraction)
    }

    /** 失敗は理由を保持する（UI がそのまま表示する）。 */
    @Test
    fun failedKeepsReason() {
        assertEquals("照合に失敗しました", UpdateInstallState.Failed("照合に失敗しました").reason)
    }
}
