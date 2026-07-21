package to.sava.peranta.ui.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReceiveSetupStepsTest {

    /** 手順は 5 つが規定順で並ぶ。 */
    @Test
    fun orderedIdsAreTheFiveStepsInOrder() {
        assertEquals(
            listOf("ntfy-installed", "up-server-config", "unifiedpush", "ntfy-battery", "up-self-test"),
            ReceiveSetupSteps.orderedIds,
        )
    }

    /** 番号は並び順に 1 始まりで振られる。 */
    @Test
    fun numberOfMatchesOrderedPosition() {
        assertEquals(1, ReceiveSetupSteps.numberOf(ReceiveSetupSteps.NTFY_INSTALLED_ID))
        assertEquals(2, ReceiveSetupSteps.numberOf(ReceiveSetupSteps.SERVER_CONFIG_ID))
        assertEquals(3, ReceiveSetupSteps.numberOf(ReceiveSetupSteps.UNIFIED_PUSH_ID))
        assertEquals(4, ReceiveSetupSteps.numberOf(ReceiveSetupSteps.NTFY_BATTERY_ID))
        assertEquals(5, ReceiveSetupSteps.numberOf(ReceiveSetupSteps.SELF_TEST_ID))
    }

    /** タイトルは画面の手順表示どおり。 */
    @Test
    fun titlesMatchSpecTable() {
        assertEquals("ntfy アプリの導入", ReceiveSetupSteps.titleOf(ReceiveSetupSteps.NTFY_INSTALLED_ID))
        assertEquals("ntfy にサーバを設定", ReceiveSetupSteps.titleOf(ReceiveSetupSteps.SERVER_CONFIG_ID))
        assertEquals("UnifiedPush の登録", ReceiveSetupSteps.titleOf(ReceiveSetupSteps.UNIFIED_PUSH_ID))
        assertEquals("ntfy を省電力から除外", ReceiveSetupSteps.titleOf(ReceiveSetupSteps.NTFY_BATTERY_ID))
        assertEquals("受信テスト", ReceiveSetupSteps.titleOf(ReceiveSetupSteps.SELF_TEST_ID))
    }

    /** 誘導文は番号とタイトルを組み合わせた定型文になる。 */
    @Test
    fun guidanceToComposesNumberAndTitle() {
        assertEquals(
            "受信のセットアップ 手順2「ntfy にサーバを設定」で直せます",
            ReceiveSetupSteps.guidanceTo(ReceiveSetupSteps.SERVER_CONFIG_ID),
        )
        assertEquals(
            "受信のセットアップ 手順1「ntfy アプリの導入」で直せます",
            ReceiveSetupSteps.guidanceTo(ReceiveSetupSteps.NTFY_INSTALLED_ID),
        )
    }

    /** 各手順は説明文を持つ。 */
    @Test
    fun everyStepHasDescription() {
        ReceiveSetupSteps.orderedIds.forEach { id ->
            assertEquals(true, ReceiveSetupSteps.descriptionOf(id).isNotBlank())
        }
    }

    /** 未知の id は番号・タイトル・説明・誘導文のいずれでもエラーにする。 */
    @Test
    fun unknownIdFails() {
        assertFailsWith<IllegalArgumentException> { ReceiveSetupSteps.numberOf("nope") }
        assertFailsWith<IllegalArgumentException> { ReceiveSetupSteps.titleOf("nope") }
        assertFailsWith<IllegalArgumentException> { ReceiveSetupSteps.descriptionOf("nope") }
        assertFailsWith<IllegalArgumentException> { ReceiveSetupSteps.guidanceTo("nope") }
    }
}
