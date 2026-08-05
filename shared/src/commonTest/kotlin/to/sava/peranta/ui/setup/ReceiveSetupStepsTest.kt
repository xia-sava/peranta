package to.sava.peranta.ui.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReceiveSetupStepsTest {

    /** 手順は 6 つが規定順で並び、認証情報はサーバ設定の直後に入る。 */
    @Test
    fun orderedIdsAreTheSixStepsInOrder() {
        assertEquals(
            listOf(
                "ntfy-installed",
                "up-server-config",
                "ntfy-credentials",
                "unifiedpush",
                "ntfy-battery",
                "up-self-test",
            ),
            ReceiveSetupSteps.orderedIds,
        )
    }

    /** 番号は並び順に 1 始まりで振られる。 */
    @Test
    fun numberOfMatchesOrderedPosition() {
        assertEquals(1, ReceiveSetupSteps.numberOf(ReceiveSetupSteps.NTFY_INSTALLED_ID))
        assertEquals(2, ReceiveSetupSteps.numberOf(ReceiveSetupSteps.SERVER_CONFIG_ID))
        assertEquals(3, ReceiveSetupSteps.numberOf(ReceiveSetupSteps.NTFY_CREDENTIALS_ID))
        assertEquals(4, ReceiveSetupSteps.numberOf(ReceiveSetupSteps.UNIFIED_PUSH_ID))
        assertEquals(5, ReceiveSetupSteps.numberOf(ReceiveSetupSteps.NTFY_BATTERY_ID))
        assertEquals(6, ReceiveSetupSteps.numberOf(ReceiveSetupSteps.SELF_TEST_ID))
    }

    /** タイトルは画面の手順表示どおり。 */
    @Test
    fun titlesMatchSpecTable() {
        assertEquals("ntfy アプリの導入", ReceiveSetupSteps.titleOf(ReceiveSetupSteps.NTFY_INSTALLED_ID))
        assertEquals("ntfy にサーバを設定", ReceiveSetupSteps.titleOf(ReceiveSetupSteps.SERVER_CONFIG_ID))
        assertEquals("ntfy に認証情報を設定", ReceiveSetupSteps.titleOf(ReceiveSetupSteps.NTFY_CREDENTIALS_ID))
        assertEquals("UnifiedPush の登録", ReceiveSetupSteps.titleOf(ReceiveSetupSteps.UNIFIED_PUSH_ID))
        assertEquals("ntfy を省電力から除外", ReceiveSetupSteps.titleOf(ReceiveSetupSteps.NTFY_BATTERY_ID))
        assertEquals("受信テスト", ReceiveSetupSteps.titleOf(ReceiveSetupSteps.SELF_TEST_ID))
    }

    /** サーバ設定の説明はデフォルトのサーバーだけを扱い、認証情報の手順の説明と重ならない。 */
    @Test
    fun serverAndCredentialDescriptionsAreSeparated() {
        val server = ReceiveSetupSteps.descriptionOf(ReceiveSetupSteps.SERVER_CONFIG_ID)
        assertEquals(true, server.contains("デフォルトのサーバー"))
        assertEquals(false, server.contains("カスタムヘッダー"))

        val credentials = ReceiveSetupSteps.descriptionOf(ReceiveSetupSteps.NTFY_CREDENTIALS_ID)
        assertEquals(true, credentials.contains("カスタムヘッダー"))
        assertEquals(true, credentials.contains("ユーザーの管理"))
    }

    /** 手順 1 つの表記は番号を伴う「手順N」になる。 */
    @Test
    fun labelOfComposesStepNumber() {
        assertEquals("手順1", ReceiveSetupSteps.labelOf(ReceiveSetupSteps.NTFY_INSTALLED_ID))
        assertEquals("手順4", ReceiveSetupSteps.labelOf(ReceiveSetupSteps.UNIFIED_PUSH_ID))
    }

    /** 連続した手順の範囲は「手順N〜M」になる。 */
    @Test
    fun rangeLabelOfComposesBothEnds() {
        assertEquals(
            "手順2〜5",
            ReceiveSetupSteps.rangeLabelOf(ReceiveSetupSteps.SERVER_CONFIG_ID, ReceiveSetupSteps.NTFY_BATTERY_ID),
        )
        assertEquals(
            "手順1〜6",
            ReceiveSetupSteps.rangeLabelOf(ReceiveSetupSteps.NTFY_INSTALLED_ID, ReceiveSetupSteps.SELF_TEST_ID),
        )
    }

    /** 手順を名指しする表記は番号とタイトルを組み合わせる。 */
    @Test
    fun referenceToComposesNumberAndTitle() {
        assertEquals(
            "手順3「ntfy に認証情報を設定」",
            ReceiveSetupSteps.referenceTo(ReceiveSetupSteps.NTFY_CREDENTIALS_ID),
        )
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

    /** 未知の id は番号・タイトル・説明・表記・誘導文のいずれでもエラーにする。 */
    @Test
    fun unknownIdFails() {
        assertFailsWith<IllegalArgumentException> { ReceiveSetupSteps.numberOf("nope") }
        assertFailsWith<IllegalArgumentException> { ReceiveSetupSteps.titleOf("nope") }
        assertFailsWith<IllegalArgumentException> { ReceiveSetupSteps.descriptionOf("nope") }
        assertFailsWith<IllegalArgumentException> { ReceiveSetupSteps.labelOf("nope") }
        assertFailsWith<IllegalArgumentException> {
            ReceiveSetupSteps.rangeLabelOf("nope", ReceiveSetupSteps.SELF_TEST_ID)
        }
        assertFailsWith<IllegalArgumentException> { ReceiveSetupSteps.referenceTo("nope") }
        assertFailsWith<IllegalArgumentException> { ReceiveSetupSteps.guidanceTo("nope") }
    }
}
