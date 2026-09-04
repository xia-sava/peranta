package to.sava.peranta.ui

import to.sava.peranta.ui.setup.ReceiveSetupSteps
import to.sava.peranta.ui.setup.SetupItemUi
import to.sava.peranta.ui.setup.SetupStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnifiedPushHealthItemsTest {

    private fun setupItem(
        id: String = ReceiveSetupSteps.UNIFIED_PUSH_ID,
        status: SetupStatus,
        statusDetail: String? = null,
    ): SetupItemUi =
        SetupItemUi(
            id = id,
            title = ReceiveSetupSteps.titleOf(id),
            description = ReceiveSetupSteps.descriptionOf(id),
            status = status,
            statusDetail = statusDetail,
        )

    private fun convert(item: SetupItemUi, onOpenSetup: () -> Unit = {}): HealthCheckItem =
        receiveSetupHealthItems(listOf(item), onOpenSetup).single()

    /** 各 SetupStatus は既存の診断状態へ写る（TODO/BLOCKED は不合格、UNKNOWN は情報、DONE は合格）。 */
    @Test
    fun statusMappingCoversAllStatuses() {
        val expected = mapOf(
            SetupStatus.DONE to HealthCheckState.PASS,
            SetupStatus.TODO to HealthCheckState.FAILING,
            SetupStatus.BLOCKED to HealthCheckState.FAILING,
            SetupStatus.UNKNOWN to HealthCheckState.INFO,
        )
        expected.forEach { (status, state) ->
            assertEquals(state, convert(setupItem(status = status)).state, "status=$status")
        }
    }

    /** ラベルは受信のセットアップ手順の「番号. タイトル」で組む。 */
    @Test
    fun labelIsNumberedStepTitle() {
        val item = convert(setupItem(id = ReceiveSetupSteps.NTFY_INSTALLED_ID, status = SetupStatus.TODO))
        assertEquals("1. ntfy アプリの導入", item.label)
    }

    /** UnifiedPush 系は診断では修復手段を持たず、誘導リンクだけを担う。 */
    @Test
    fun hasNoDirectFix() {
        val item = convert(setupItem(status = SetupStatus.TODO))
        assertNull(item.fixLabel)
        assertNull(item.onFix)
        assertNull(item.fixGuidance)
    }

    /** 直接の操作が要る未達（TODO）は、状態の事実に自手順への誘導文を添える。 */
    @Test
    fun todoDetailAppendsGuidance() {
        val fact = "受信エンドポイントが向いている先が不一致です。"
        val item = convert(setupItem(status = SetupStatus.TODO, statusDetail = fact))
        assertTrue(item.detail!!.contains(fact))
        assertTrue(item.detail.contains(ReceiveSetupSteps.guidanceTo(ReceiveSetupSteps.UNIFIED_PUSH_ID)))
    }

    /** 事実が無い未達でも、誘導文だけは detail に出す。 */
    @Test
    fun todoWithoutFactStillHasGuidance() {
        val item = convert(setupItem(id = ReceiveSetupSteps.NTFY_INSTALLED_ID, status = SetupStatus.TODO))
        assertEquals(ReceiveSetupSteps.guidanceTo(ReceiveSetupSteps.NTFY_INSTALLED_ID), item.detail)
    }

    /** 前提未達（BLOCKED）は事実が既に先行手順を指すため、自手順の誘導文を重ねない。 */
    @Test
    fun blockedDetailKeepsFactsWithoutOwnGuidance() {
        val fact = "先に手順1で ntfy を導入してください。"
        val item = convert(setupItem(id = ReceiveSetupSteps.NTFY_BATTERY_ID, status = SetupStatus.BLOCKED, statusDetail = fact))
        assertEquals(fact, item.detail)
    }

    /** 未確認（UNKNOWN）は事実だけを示し、誘導文は添えない。 */
    @Test
    fun unknownDetailKeepsFactsOnly() {
        val fact = "まだ実行していません。"
        val item = convert(setupItem(id = ReceiveSetupSteps.SELF_TEST_ID, status = SetupStatus.UNKNOWN, statusDetail = fact))
        assertEquals(fact, item.detail)
    }

    /** 合格（DONE）は事実のみで、誘導文も誘導リンクも持たない。 */
    @Test
    fun doneHasFactsAndNoLink() {
        val fact = "サーバ経由の配送を確認しました。"
        val item = convert(setupItem(id = ReceiveSetupSteps.SELF_TEST_ID, status = SetupStatus.DONE, statusDetail = fact))
        assertEquals(fact, item.detail)
        assertNull(item.link)
    }

    /** 未達の項目は「セットアップを開く」誘導リンクを持ち、押すと onOpenSetup が呼ばれる。 */
    @Test
    fun nonDoneCarriesSetupLink() {
        var opened = false
        val item = convert(setupItem(status = SetupStatus.TODO), onOpenSetup = { opened = true })
        assertEquals("セットアップを開く", item.link!!.label)
        item.link.onOpen()
        assertTrue(opened)
    }

    /** 情報項目（UNKNOWN）にも誘導リンクを出す。 */
    @Test
    fun unknownAlsoCarriesSetupLink() {
        val item = convert(setupItem(id = ReceiveSetupSteps.SELF_TEST_ID, status = SetupStatus.UNKNOWN))
        assertEquals("セットアップを開く", item.link!!.label)
    }

    /** 手順の列は同じ順序・id で診断項目列へ写る。 */
    @Test
    fun preservesOrderAndIds() {
        val items = ReceiveSetupSteps.orderedIds.map { setupItem(id = it, status = SetupStatus.TODO) }
        val converted = receiveSetupHealthItems(items, {})
        assertEquals(ReceiveSetupSteps.orderedIds, converted.map { it.id })
    }
}
