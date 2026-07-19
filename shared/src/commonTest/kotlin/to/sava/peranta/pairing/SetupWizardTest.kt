package to.sava.peranta.pairing

import to.sava.peranta.config.PerantaConfig
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupWizardTest {

    private fun paired(config: PerantaConfig = PerantaConfig()): PerantaConfig =
        config.copy(sharedKeyBase64 = Base64.encode(ByteArray(32)), keyId = "1")

    /** 送信ロールは CONNECTION から始まり PAIRING で終わる 4 ステップ。 */
    @Test
    fun senderStepsCoverFullSequence() {
        assertEquals(
            listOf(SetupStep.CONNECTION, SetupStep.DEVICE, SetupStep.KEY, SetupStep.PAIRING),
            SetupWizard.steps(SetupRole.SENDER),
        )
    }

    /** 受信専用ロールは CONNECTION/KEY を持たず PAIRING から始まる。 */
    @Test
    fun receiverStepsStartFromPairingAndSkipConnectionAndKey() {
        val steps = SetupWizard.steps(SetupRole.RECEIVER)
        assertEquals(listOf(SetupStep.PAIRING, SetupStep.DEVICE), steps)
        assertFalse(steps.contains(SetupStep.CONNECTION))
        assertFalse(steps.contains(SetupStep.KEY))
    }

    /** CONNECTION は host と token が揃えば完了。 */
    @Test
    fun connectionProceedsWithHostAndToken() {
        assertFalse(SetupWizard.canProceed(SetupStep.CONNECTION, PerantaConfig(accessToken = null), SetupRole.SENDER))
        assertTrue(
            SetupWizard.canProceed(
                SetupStep.CONNECTION,
                PerantaConfig(host = "h", accessToken = "tk"),
                SetupRole.SENDER,
            ),
        )
    }

    /** DEVICE は端末名があれば完了。 */
    @Test
    fun deviceProceedsWithDeviceName() {
        assertFalse(SetupWizard.canProceed(SetupStep.DEVICE, PerantaConfig(deviceName = "  "), SetupRole.SENDER))
        assertTrue(SetupWizard.canProceed(SetupStep.DEVICE, PerantaConfig(deviceName = "d"), SetupRole.SENDER))
    }

    /** KEY は共有鍵と keyId が揃えば完了。 */
    @Test
    fun keyProceedsWhenSharedKeyPresent() {
        assertFalse(SetupWizard.canProceed(SetupStep.KEY, PerantaConfig(), SetupRole.SENDER))
        assertTrue(SetupWizard.canProceed(SetupStep.KEY, paired(), SetupRole.SENDER))
    }

    /** 受信専用ロールの PAIRING は QR 取り込みによる共有鍵の有無だけで完了判定する。 */
    @Test
    fun pairingProceedsForReceiverWhenSharedKeyPresent() {
        assertFalse(SetupWizard.canProceed(SetupStep.PAIRING, PerantaConfig(), SetupRole.RECEIVER))
        assertTrue(SetupWizard.canProceed(SetupStep.PAIRING, paired(), SetupRole.RECEIVER))
    }

    /** 送信ロールの PAIRING は共有鍵だけでは不十分で、isReadyForSend 相当（controlTopic 等）が必要。 */
    @Test
    fun pairingRequiresReadyForSendForSender() {
        val keyOnly = paired(PerantaConfig(host = "h", accessToken = "tk", deviceName = "d"))
        assertFalse(SetupWizard.canProceed(SetupStep.PAIRING, keyOnly, SetupRole.SENDER))

        val readyForSend = keyOnly.copy(controlTopic = "control-topic")
        assertTrue(SetupWizard.canProceed(SetupStep.PAIRING, readyForSend, SetupRole.SENDER))
    }

    /** 送信ロールの最初の未完了ステップ: 何も無ければ CONNECTION。 */
    @Test
    fun firstIncompleteStepForEmptySenderIsConnection() {
        assertEquals(
            SetupStep.CONNECTION,
            SetupWizard.firstIncompleteStep(PerantaConfig(), SetupRole.SENDER),
        )
    }

    /** 送信ロール: 接続と端末名が揃えば次は KEY。 */
    @Test
    fun firstIncompleteStepForSenderSkipsCompletedSteps() {
        val config = PerantaConfig(host = "h", accessToken = "tk", deviceName = "d")
        assertEquals(SetupStep.KEY, SetupWizard.firstIncompleteStep(config, SetupRole.SENDER))
    }

    /** 受信専用ロールの最初の未完了ステップ: 何も無ければ PAIRING。 */
    @Test
    fun firstIncompleteStepForEmptyReceiverIsPairing() {
        assertEquals(
            SetupStep.PAIRING,
            SetupWizard.firstIncompleteStep(PerantaConfig(), SetupRole.RECEIVER),
        )
    }

    /** 受信専用ロール: 取り込み済みなら残るは端末名。 */
    @Test
    fun firstIncompleteStepForReceiverAfterPairingIsDevice() {
        assertEquals(
            SetupStep.DEVICE,
            SetupWizard.firstIncompleteStep(paired(), SetupRole.RECEIVER),
        )
    }

    /** 全ステップ完了なら firstIncompleteStep は null。 */
    @Test
    fun firstIncompleteStepIsNullWhenAllComplete() {
        val config = paired(
            PerantaConfig(host = "h", accessToken = "tk", deviceName = "d", controlTopic = "control-topic"),
        )
        assertNull(SetupWizard.firstIncompleteStep(config, SetupRole.SENDER))
        assertNull(SetupWizard.firstIncompleteStep(config, SetupRole.RECEIVER))
    }

    /** 送信ロール: KEY まで完了しても QR 未表示（controlTopic 未採番）なら PAIRING が残る。 */
    @Test
    fun firstIncompleteStepForSenderIsPairingWhenQrNotShownYet() {
        val config = paired(PerantaConfig(host = "h", accessToken = "tk", deviceName = "d"))
        assertEquals(SetupStep.PAIRING, SetupWizard.firstIncompleteStep(config, SetupRole.SENDER))
    }

    /** 送信ロール: QR 表示（controlTopic 採番）後は firstIncompleteStep が null になる。 */
    @Test
    fun firstIncompleteStepForSenderIsNullAfterControlTopicAssigned() {
        val config = paired(
            PerantaConfig(host = "h", accessToken = "tk", deviceName = "d", controlTopic = "control-topic"),
        )
        assertNull(SetupWizard.firstIncompleteStep(config, SetupRole.SENDER))
    }

    /** 受信専用ロール: 端末名だけ先に設定済み・鍵なしでも firstIncompleteStep は PAIRING を返す。 */
    @Test
    fun firstIncompleteStepForReceiverIsPairingWhenOnlyDeviceNameSet() {
        val config = PerantaConfig(deviceName = "d")
        assertEquals(SetupStep.PAIRING, SetupWizard.firstIncompleteStep(config, SetupRole.RECEIVER))
    }

    /** 送信ロール: 接続情報だけ完了・端末名未設定の中間段階では firstIncompleteStep が DEVICE を返す。 */
    @Test
    fun firstIncompleteStepForSenderIsDeviceWhenOnlyConnectionComplete() {
        val config = PerantaConfig(host = "h", accessToken = "tk")
        assertEquals(SetupStep.DEVICE, SetupWizard.firstIncompleteStep(config, SetupRole.SENDER))
    }

    /** next/previous はロールのステップ列内を前後移動し、両端では null。 */
    @Test
    fun nextAndPreviousMoveWithinRoleSequence() {
        assertEquals(SetupStep.DEVICE, SetupWizard.next(SetupStep.CONNECTION, SetupRole.SENDER))
        assertNull(SetupWizard.next(SetupStep.PAIRING, SetupRole.SENDER))
        assertEquals(SetupStep.KEY, SetupWizard.previous(SetupStep.PAIRING, SetupRole.SENDER))
        assertNull(SetupWizard.previous(SetupStep.CONNECTION, SetupRole.SENDER))

        assertEquals(SetupStep.DEVICE, SetupWizard.next(SetupStep.PAIRING, SetupRole.RECEIVER))
        assertNull(SetupWizard.next(SetupStep.DEVICE, SetupRole.RECEIVER))
        assertEquals(SetupStep.PAIRING, SetupWizard.previous(SetupStep.DEVICE, SetupRole.RECEIVER))
        assertNull(SetupWizard.previous(SetupStep.PAIRING, SetupRole.RECEIVER))
    }

    /** ステップ列に含まれないステップからの前後移動は null。 */
    @Test
    fun nextAndPreviousReturnNullForStepOutsideRole() {
        assertNull(SetupWizard.next(SetupStep.CONNECTION, SetupRole.RECEIVER))
        assertNull(SetupWizard.previous(SetupStep.KEY, SetupRole.RECEIVER))
    }
}
