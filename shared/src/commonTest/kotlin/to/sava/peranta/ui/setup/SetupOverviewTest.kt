package to.sava.peranta.ui.setup

import to.sava.peranta.ui.HealthCheckItem
import to.sava.peranta.ui.HealthCheckState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupOverviewTest {

    private fun health(id: String, state: HealthCheckState): HealthCheckItem =
        HealthCheckItem(id = id, label = id, state = state)

    private fun setup(id: String, status: SetupStatus): SetupItemUi =
        SetupItemUi(id = id, title = id, description = null, status = status, statusDetail = null)

    private fun rowOf(rows: List<SetupOverviewRow>, id: String): SetupOverviewRow =
        rows.first { it.id == id }

    private fun overview(
        hasHost: Boolean = true,
        hasToken: Boolean = true,
        hasSharedKey: Boolean = true,
        healthItems: List<HealthCheckItem>? = emptyList(),
        hasReceiveSetup: Boolean = false,
        receiveSetupItems: List<SetupItemUi>? = null,
    ): List<SetupOverviewRow> = setupOverview(
        hasHost = hasHost,
        hasToken = hasToken,
        hasSharedKey = hasSharedKey,
        healthItems = healthItems,
        hasReceiveSetup = hasReceiveSetup,
        receiveSetupItems = receiveSetupItems,
    )

    /**
     * 接続先（ホスト名・トークン）と共有鍵が揃えば接続とペアリングは達成だが、誘導先（接続設定と暗号キーの取り込み）
     * は達成状態でも常設する（鍵ローテ後の読み直し等、再取り込みの余地があるため）。
     */
    @Test
    fun connectionMetWhenAllConfigured() {
        val row = rowOf(overview(), OVERVIEW_ROW_CONNECTION)
        assertEquals(SetupOverviewStatus.MET, row.status)
        assertEquals("接続先と共有鍵設定済み", row.detail)
        assertEquals(SetupOverviewTarget.PairingImport, row.target)
        assertEquals("接続設定と暗号キーを取り込む", row.openLabel)
    }

    /** 共有鍵が無ければ接続とペアリングは未達で、足りない項目を列挙する。 */
    @Test
    fun connectionUnmetWhenNoSharedKey() {
        val row = rowOf(overview(hasSharedKey = false), OVERVIEW_ROW_CONNECTION)
        assertEquals(SetupOverviewStatus.UNMET, row.status)
        assertEquals("未設定: 共有鍵", row.detail)
    }

    /** 接続先が欠けると共有鍵があっても未達で、欠けた項目を全て列挙する。 */
    @Test
    fun connectionUnmetListsAllMissingItems() {
        val row = rowOf(overview(hasHost = false, hasToken = false), OVERVIEW_ROW_CONNECTION)
        assertEquals(SetupOverviewStatus.UNMET, row.status)
        assertEquals("未設定: サーバホスト名・アクセストークン", row.detail)
    }

    /** 動作チェック項目が未取得（null）なら通知の転送は未確認で、誘導先は動作チェック。 */
    @Test
    fun forwardUnknownWhileLoading() {
        val rows = overview(healthItems = null, hasReceiveSetup = false, receiveSetupItems = null)
        val row = rowOf(rows, OVERVIEW_ROW_FORWARD)
        assertEquals(SetupOverviewStatus.UNKNOWN, row.status)
        assertEquals(SetupOverviewTarget.HealthCheck, row.target)
    }

    /** 権限・常駐系の不合格数を数えて未達件数を出す。受信経路の項目は数に含めない。 */
    @Test
    fun forwardCountsPermissionFailuresExcludingReceivePath() {
        val items = listOf(
            health("nls", HealthCheckState.FAILING),
            health("self-battery", HealthCheckState.FAILING),
            health(ReceiveSetupSteps.UNIFIED_PUSH_ID, HealthCheckState.FAILING),
            health("post-notifications", HealthCheckState.PASS),
        )
        val row = rowOf(
            overview(healthItems = items, hasReceiveSetup = false, receiveSetupItems = null),
            OVERVIEW_ROW_FORWARD,
        )
        assertEquals(SetupOverviewStatus.UNMET, row.status)
        assertEquals("未達2件", row.detail)
    }

    /** 権限・常駐系に不合格が無ければ達成（受信経路の不合格は無視する）。 */
    @Test
    fun forwardMetWhenNoPermissionFailures() {
        val items = listOf(
            health("nls", HealthCheckState.PASS),
            health(ReceiveSetupSteps.SELF_TEST_ID, HealthCheckState.FAILING),
        )
        val row = rowOf(
            overview(healthItems = items, hasReceiveSetup = false, receiveSetupItems = null),
            OVERVIEW_ROW_FORWARD,
        )
        assertEquals(SetupOverviewStatus.MET, row.status)
        assertNull(row.detail)
    }

    /** 受信経路の行は hasReceiveSetup が false のとき出さない（Desktop）。 */
    @Test
    fun receiveRowOmittedWhenNotApplicable() {
        val rows = overview(healthItems = emptyList(), hasReceiveSetup = false, receiveSetupItems = null)
        assertTrue(rows.none { it.id == OVERVIEW_ROW_RECEIVE })
        assertEquals(2, rows.size)
    }

    /** 受信経路の項目が未取得（null）なら未確認で、誘導先は受信のセットアップ。 */
    @Test
    fun receiveUnknownWhileLoading() {
        val row = rowOf(
            overview(healthItems = emptyList(), hasReceiveSetup = true, receiveSetupItems = null),
            OVERVIEW_ROW_RECEIVE,
        )
        assertEquals(SetupOverviewStatus.UNKNOWN, row.status)
        assertEquals(SetupOverviewTarget.ReceiveSetup, row.target)
    }

    /** 受信経路は未達（TODO/BLOCKED）の件数を出す。 */
    @Test
    fun receiveUnmetCountsTodoAndBlocked() {
        val items = listOf(
            setup("a", SetupStatus.TODO),
            setup("b", SetupStatus.BLOCKED),
            setup("c", SetupStatus.DONE),
        )
        val row = rowOf(
            overview(healthItems = emptyList(), hasReceiveSetup = true, receiveSetupItems = items),
            OVERVIEW_ROW_RECEIVE,
        )
        assertEquals(SetupOverviewStatus.UNMET, row.status)
        assertEquals("未達2件", row.detail)
    }

    /** 未達が無く未確認（UNKNOWN）が残るなら未確認とし、受信テスト未実行を添える。 */
    @Test
    fun receiveUnknownWhenOnlyUnknownRemains() {
        val items = listOf(
            setup("a", SetupStatus.DONE),
            setup("b", SetupStatus.UNKNOWN),
        )
        val row = rowOf(
            overview(healthItems = emptyList(), hasReceiveSetup = true, receiveSetupItems = items),
            OVERVIEW_ROW_RECEIVE,
        )
        assertEquals(SetupOverviewStatus.UNKNOWN, row.status)
        assertEquals("未確認（受信テスト未実行）", row.detail)
    }

    /** 受信経路が全て充足なら達成。 */
    @Test
    fun receiveMetWhenAllDone() {
        val items = listOf(setup("a", SetupStatus.DONE), setup("b", SetupStatus.DONE))
        val row = rowOf(
            overview(healthItems = emptyList(), hasReceiveSetup = true, receiveSetupItems = items),
            OVERVIEW_ROW_RECEIVE,
        )
        assertEquals(SetupOverviewStatus.MET, row.status)
        assertNull(row.detail)
    }

    /** 受信経路が使えるときは 3 行で、順序は接続→転送→受信。 */
    @Test
    fun threeRowsInOrderWhenReceiveApplicable() {
        val rows = overview(healthItems = emptyList(), hasReceiveSetup = true, receiveSetupItems = emptyList())
        assertEquals(
            listOf(OVERVIEW_ROW_CONNECTION, OVERVIEW_ROW_FORWARD, OVERVIEW_ROW_RECEIVE),
            rows.map { it.id },
        )
    }
}
