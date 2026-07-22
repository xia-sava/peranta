package to.sava.peranta.ui.setup

import to.sava.peranta.net.EndpointServerMatch
import to.sava.peranta.net.SelfTestResult
import to.sava.peranta.net.SelfTestStatus
import to.sava.peranta.ui.FixAid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReceiveSetupItemsTest {

    private fun build(
        ntfyInstalled: Boolean = true,
        endpointMatch: EndpointServerMatch? = EndpointServerMatch.Match,
        upRegistered: Boolean = true,
        ntfyBatteryIgnored: Boolean = true,
        selfTestStatus: SelfTestStatus = SelfTestStatus.Done(SelfTestResult.Delivered, 0L),
        selfTestRunnable: Boolean = true,
        ntfyServerAids: List<FixAid> = listOf(FixAid.Copy(label = "サーバーURL", value = "https://example.com")),
    ): List<SetupItemUi> = receiveSetupItems(
        ntfyInstalled = ntfyInstalled,
        endpointMatch = endpointMatch,
        upRegistered = upRegistered,
        ntfyBatteryIgnored = ntfyBatteryIgnored,
        selfTestStatus = selfTestStatus,
        selfTestRunnable = selfTestRunnable,
        ntfyServerAids = ntfyServerAids,
        onInstallNtfy = {},
        onRegister = {},
        onReregister = {},
        onOpenNtfyBattery = {},
        onRunSelfTest = {},
    )

    private fun List<SetupItemUi>.byId(id: String): SetupItemUi = first { it.id == id }

    /** 手順は必ず ReceiveSetupSteps.orderedIds の順で並ぶ（番号が index+1 由来のため）。 */
    @Test
    fun itemsFollowOrderedIds() {
        assertEquals(ReceiveSetupSteps.orderedIds, build().map { it.id })
    }

    /** タイトルと説明文は ReceiveSetupSteps から取り、この関数では二重所有しない。 */
    @Test
    fun titleAndDescriptionComeFromSteps() {
        build().forEach { item ->
            assertEquals(ReceiveSetupSteps.titleOf(item.id), item.title)
            assertEquals(ReceiveSetupSteps.descriptionOf(item.id), item.description)
        }
    }

    /** 手順2は三値 — 一致で DONE、未払い出しで UNKNOWN、不一致で TODO（不一致は事実記述を添える）。 */
    @Test
    fun serverConfigIsThreeValued() {
        val match = build(endpointMatch = EndpointServerMatch.Match).byId(ReceiveSetupSteps.SERVER_CONFIG_ID)
        assertEquals(SetupStatus.DONE, match.status)

        val unknown = build(endpointMatch = null, upRegistered = false).byId(ReceiveSetupSteps.SERVER_CONFIG_ID)
        assertEquals(SetupStatus.UNKNOWN, unknown.status)

        val mismatch = build(
            endpointMatch = EndpointServerMatch.Mismatch("https://a", "https://b"),
        ).byId(ReceiveSetupSteps.SERVER_CONFIG_ID)
        assertEquals(SetupStatus.TODO, mismatch.status)
        assertEquals(
            "手順3の照合で不一致です。ntfy のデフォルトのサーバーがこのアプリの設定サーバと一致していないため、" +
                "転送された通知がこの端末に届きません。",
            mismatch.statusDetail,
        )
    }

    /** 手順2の Unparseable は不一致とは別の事実（解釈不能）を示し、要対処（TODO）として登録し直しへ誘導する。 */
    @Test
    fun serverConfigUnparseableIsActionableWithOwnDetail() {
        val item = build(endpointMatch = EndpointServerMatch.Unparseable)
            .byId(ReceiveSetupSteps.SERVER_CONFIG_ID)
        assertEquals(SetupStatus.TODO, item.status)
        assertTrue(item.statusDetail!!.contains("解釈できません"))
        assertTrue(item.statusDetail!!.contains("手順3"))
        assertTrue(!item.statusDetail!!.contains("不一致"))
    }

    /** 手順2は状態に依らず貼り付け値（コピーチップ等）を常設する。 */
    @Test
    fun serverConfigAlwaysCarriesAids() {
        val aids = listOf(
            FixAid.Copy(label = "サーバーURL", value = "https://example.com"),
            FixAid.Action(label = "ntfy を開く", onRun = {}),
        )
        listOf(EndpointServerMatch.Match, null, EndpointServerMatch.Mismatch("a", "b")).forEach { match ->
            val item = build(endpointMatch = match, ntfyServerAids = aids)
                .byId(ReceiveSetupSteps.SERVER_CONFIG_ID)
            assertEquals(aids, item.aids)
        }
    }

    /** 手順3は登録状態でラベルだけ入れ替え（位置固定）、照合結果を事実記述に写す。 */
    @Test
    fun unifiedPushActionLabelSwapsAndDetailReflectsMatch() {
        val registered = build(upRegistered = true, endpointMatch = EndpointServerMatch.Match)
            .byId(ReceiveSetupSteps.UNIFIED_PUSH_ID)
        assertEquals(SetupStatus.DONE, registered.status)
        assertEquals("登録し直す", registered.action?.label)
        assertNotNull(registered.statusDetail)

        val unregistered = build(upRegistered = false, endpointMatch = null)
            .byId(ReceiveSetupSteps.UNIFIED_PUSH_ID)
        assertEquals(SetupStatus.TODO, unregistered.status)
        assertEquals("登録する", unregistered.action?.label)
        assertNull(unregistered.statusDetail)
    }

    /** 手順4は ntfy 未導入なら前提未達（BLOCKED）にし、手順1を参照させる。 */
    @Test
    fun ntfyBatteryBlockedWhenNtfyMissing() {
        val blocked = build(ntfyInstalled = false).byId(ReceiveSetupSteps.NTFY_BATTERY_ID)
        assertEquals(SetupStatus.BLOCKED, blocked.status)
        assertNotNull(blocked.statusDetail)

        val done = build(ntfyInstalled = true, ntfyBatteryIgnored = true).byId(ReceiveSetupSteps.NTFY_BATTERY_ID)
        assertEquals(SetupStatus.DONE, done.status)
    }

    /** 手順5は結果で状態を分け、ラベルは未実行「テスト実行」/実行後「再実行」に変わる。 */
    @Test
    fun selfTestStatusAndLabelReflectResult() {
        val notRun = build(selfTestStatus = SelfTestStatus.NotRun).byId(ReceiveSetupSteps.SELF_TEST_ID)
        assertEquals(SetupStatus.UNKNOWN, notRun.status)
        assertEquals("テスト実行", notRun.action?.label)

        val delivered = build(selfTestStatus = SelfTestStatus.Done(SelfTestResult.Delivered, 0L))
            .byId(ReceiveSetupSteps.SELF_TEST_ID)
        assertEquals(SetupStatus.DONE, delivered.status)
        assertEquals("再実行", delivered.action?.label)

        val failed = build(selfTestStatus = SelfTestStatus.Done(SelfTestResult.Timeout, 0L))
            .byId(ReceiveSetupSteps.SELF_TEST_ID)
        assertEquals(SetupStatus.TODO, failed.status)
        assertTrue(failed.statusDetail!!.contains("手順2〜4"))
    }

    /**
     * 手順5の実行前提欠落は二値 — エンドポイント未払い出しは前提未達（BLOCKED）で手順3を参照、
     * トークン未設定は受信を妨げない構成なので合否を出さず（UNKNOWN）必要な旨だけ示す。
     */
    @Test
    fun selfTestPrerequisitesSplitBlockedAndUnknown() {
        val noEndpoint = build(endpointMatch = null, selfTestRunnable = false, upRegistered = false)
            .byId(ReceiveSetupSteps.SELF_TEST_ID)
        assertEquals(SetupStatus.BLOCKED, noEndpoint.status)
        assertEquals("先に手順3で登録してください。", noEndpoint.statusDetail)
        assertNotNull(noEndpoint.action)

        val noToken = build(endpointMatch = EndpointServerMatch.Match, selfTestRunnable = false)
            .byId(ReceiveSetupSteps.SELF_TEST_ID)
        assertEquals(SetupStatus.UNKNOWN, noToken.status)
        assertTrue(noToken.statusDetail!!.contains("アクセストークン"))
        assertNotNull(noToken.action)
    }

    /** 失敗時の対処は自手順参照に留め、ntfy の具体操作手順を重複記述しない。 */
    @Test
    fun selfTestFailureDetailReferencesOwnStepsOnly() {
        listOf(
            SelfTestResult.Timeout,
            SelfTestResult.PublishRejected(403),
            SelfTestResult.PublishFailed,
        ).forEach { result ->
            val detail = build(selfTestStatus = SelfTestStatus.Done(result, 0L))
                .byId(ReceiveSetupSteps.SELF_TEST_ID).statusDetail
            assertTrue(detail!!.contains("手順2〜4を確認してください"))
            assertTrue(!detail.contains("デフォルトのサーバー") && !detail.contains("カスタムヘッダー"))
        }
    }

    /** オールグリーンでも全手順に主操作またはコピーチップが残る（常設性）。 */
    @Test
    fun allGreenStillKeepsActionsAndAids() {
        build(
            ntfyInstalled = true,
            endpointMatch = EndpointServerMatch.Match,
            upRegistered = true,
            ntfyBatteryIgnored = true,
            selfTestStatus = SelfTestStatus.Done(SelfTestResult.Delivered, 0L),
        ).forEach { item ->
            assertTrue(item.action != null || item.aids.isNotEmpty(), "手順 ${item.id} に操作が残っていない")
        }
    }
}
