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
        otherDistributors: List<String> = emptyList(),
        endpointMatch: EndpointServerMatch? = EndpointServerMatch.Match,
        credentialProof: NtfyCredentialProof = NtfyCredentialProof.CONFIRMED,
        upRegistered: Boolean = true,
        ntfyBatteryIgnored: Boolean = true,
        selfTestStatus: SelfTestStatus = SelfTestStatus.Done(SelfTestResult.Delivered, 0L),
        selfTestRunnable: Boolean = true,
        ntfyServerAids: List<FixAid> = listOf(FixAid.Copy(label = "サーバーURL", value = "https://example.com")),
        ntfyCredentialAids: List<FixAid> = listOf(FixAid.Copy(label = "ヘッダ名", value = "Authorization")),
    ): List<SetupItemUi> = receiveSetupItems(
        ntfyInstalled = ntfyInstalled,
        otherDistributors = otherDistributors,
        endpointMatch = endpointMatch,
        credentialProof = credentialProof,
        upRegistered = upRegistered,
        ntfyBatteryIgnored = ntfyBatteryIgnored,
        selfTestStatus = selfTestStatus,
        selfTestRunnable = selfTestRunnable,
        ntfyServerAids = ntfyServerAids,
        ntfyCredentialAids = ntfyCredentialAids,
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

    /** サーバ設定は三値 — 一致で DONE、未払い出しで UNKNOWN、不一致で TODO（不一致は事実記述を添える）。 */
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
            "手順4の照合で不一致です。ntfy のデフォルトのサーバーがこのアプリの設定サーバと一致していないため、" +
                "転送された通知がこの端末に届きません。",
            mismatch.statusDetail,
        )
    }

    /** サーバ設定の Unparseable は不一致とは別の事実（解釈不能）を示し、要対処（TODO）として登録し直しへ誘導する。 */
    @Test
    fun serverConfigUnparseableIsActionableWithOwnDetail() {
        val item = build(endpointMatch = EndpointServerMatch.Unparseable)
            .byId(ReceiveSetupSteps.SERVER_CONFIG_ID)
        assertEquals(SetupStatus.TODO, item.status)
        assertTrue(item.statusDetail!!.contains("解釈できません"))
        assertTrue(item.statusDetail!!.contains("手順4"))
        assertTrue(!item.statusDetail!!.contains("不一致"))
    }

    /** サーバ設定は状態に依らず貼り付け値（コピーチップ等）を常設する。 */
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

    /** 認証情報は受信テストの合格を根拠に三値で出す — 現トークンで合格 DONE、失効 TODO、未合格 UNKNOWN。 */
    @Test
    fun credentialStatusFollowsSelfTestProof() {
        val confirmed = build(credentialProof = NtfyCredentialProof.CONFIRMED)
            .byId(ReceiveSetupSteps.NTFY_CREDENTIALS_ID)
        assertEquals(SetupStatus.DONE, confirmed.status)

        val stale = build(credentialProof = NtfyCredentialProof.STALE).byId(ReceiveSetupSteps.NTFY_CREDENTIALS_ID)
        assertEquals(SetupStatus.TODO, stale.status)
        assertTrue(stale.statusDetail!!.contains("アクセストークンが変わっています"))

        val unconfirmed = build(credentialProof = NtfyCredentialProof.UNCONFIRMED)
            .byId(ReceiveSetupSteps.NTFY_CREDENTIALS_ID)
        assertEquals(SetupStatus.UNKNOWN, unconfirmed.status)
        assertTrue(unconfirmed.statusDetail!!.contains("手順6「受信テスト」"))
    }

    /** 認証情報は状態に依らず貼り付け値（カスタムヘッダーのコピーチップ）を常設する。 */
    @Test
    fun credentialAlwaysCarriesAids() {
        val aids = listOf(
            FixAid.Copy(label = "カスタムヘッダーのヘッダ名", value = "Authorization"),
            FixAid.Action(label = "ntfy を開く", onRun = {}),
        )
        NtfyCredentialProof.entries.forEach { proof ->
            val item = build(credentialProof = proof, ntfyCredentialAids = aids)
                .byId(ReceiveSetupSteps.NTFY_CREDENTIALS_ID)
            assertEquals(aids, item.aids)
        }
    }

    /** UnifiedPush 登録は登録状態でラベルだけ入れ替え（位置固定）、照合結果を事実記述に写す。 */
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

    /** ntfy 導入は未導入かつ他のディストリビュータが居るときだけ、採用しない旨を事実として添える。 */
    @Test
    fun ntfyInstalledDetailMentionsOtherDistributorsOnlyWhenNtfyMissing() {
        val withOthers = build(ntfyInstalled = false, otherDistributors = listOf("Sunup", "NextPush"))
            .byId(ReceiveSetupSteps.NTFY_INSTALLED_ID)
        assertEquals(SetupStatus.TODO, withOthers.status)
        assertTrue(withOthers.statusDetail!!.contains("Sunup"))
        assertTrue(withOthers.statusDetail!!.contains("NextPush"))

        val onlyOthersMissing = build(ntfyInstalled = false).byId(ReceiveSetupSteps.NTFY_INSTALLED_ID)
        assertNull(onlyOthersMissing.statusDetail)

        val installed = build(ntfyInstalled = true, otherDistributors = listOf("Sunup"))
            .byId(ReceiveSetupSteps.NTFY_INSTALLED_ID)
        assertEquals(SetupStatus.DONE, installed.status)
        assertNull(installed.statusDetail)
    }

    /** 省電力除外は ntfy 未導入なら前提未達（BLOCKED）にし、ntfy 導入の手順を参照させる。 */
    @Test
    fun ntfyBatteryBlockedWhenNtfyMissing() {
        val blocked = build(ntfyInstalled = false).byId(ReceiveSetupSteps.NTFY_BATTERY_ID)
        assertEquals(SetupStatus.BLOCKED, blocked.status)
        assertNotNull(blocked.statusDetail)

        val done = build(ntfyInstalled = true, ntfyBatteryIgnored = true).byId(ReceiveSetupSteps.NTFY_BATTERY_ID)
        assertEquals(SetupStatus.DONE, done.status)
    }

    /** 受信テストは結果で状態を分け、ラベルは未実行「テスト実行」/実行後「再実行」に変わる。 */
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
        assertTrue(failed.statusDetail!!.contains("手順2〜5"))
    }

    /**
     * 受信テストの実行前提欠落は二値 — エンドポイント未払い出しは前提未達（BLOCKED）で UnifiedPush 登録を参照、
     * トークン未設定は受信を妨げない構成なので合否を出さず（UNKNOWN）必要な旨だけ示す。
     */
    @Test
    fun selfTestPrerequisitesSplitBlockedAndUnknown() {
        val noEndpoint = build(endpointMatch = null, selfTestRunnable = false, upRegistered = false)
            .byId(ReceiveSetupSteps.SELF_TEST_ID)
        assertEquals(SetupStatus.BLOCKED, noEndpoint.status)
        assertEquals("先に手順4で登録してください。", noEndpoint.statusDetail)
        assertNotNull(noEndpoint.action)

        val noToken = build(endpointMatch = EndpointServerMatch.Match, selfTestRunnable = false)
            .byId(ReceiveSetupSteps.SELF_TEST_ID)
        assertEquals(SetupStatus.UNKNOWN, noToken.status)
        assertTrue(noToken.statusDetail!!.contains("アクセストークン"))
        assertNotNull(noToken.action)
    }

    /** 合格の根拠が生きているときの失敗の対処は自手順参照に留め、ntfy の具体操作手順を重複記述しない。 */
    @Test
    fun selfTestFailureDetailReferencesOwnStepsOnly() {
        listOf(
            SelfTestResult.Timeout,
            SelfTestResult.PublishRejected(403),
            SelfTestResult.PublishFailed,
        ).forEach { result ->
            val detail = build(selfTestStatus = SelfTestStatus.Done(result, 0L))
                .byId(ReceiveSetupSteps.SELF_TEST_ID).statusDetail
            assertTrue(detail!!.contains("手順2〜5を確認してください"))
            assertTrue(!detail.contains("デフォルトのサーバー") && !detail.contains("カスタムヘッダー"))
        }
    }

    /**
     * 前回の合格からトークンが変わっているときの不達は、汎用の見直し案内ではなく
     * 認証情報の手順へ絞って案内する（合格の根拠が失効している以上、そこが最有力なため）。
     */
    @Test
    fun timeoutPointsAtCredentialStepWhenTokenChangedSincePass() {
        val detail = build(
            credentialProof = NtfyCredentialProof.STALE,
            selfTestStatus = SelfTestStatus.Done(SelfTestResult.Timeout, 0L),
        ).byId(ReceiveSetupSteps.SELF_TEST_ID).statusDetail

        assertTrue(detail!!.contains("アクセストークンが変わっています"))
        assertTrue(detail.contains("手順3「ntfy に認証情報を設定」"))
        assertTrue(!detail.contains("手順2〜5"))
    }

    /** publish 自体が失敗した結末は、トークンが変わっていても汎用の見直し案内のままにする。 */
    @Test
    fun publishFailuresKeepGenericGuidanceEvenWhenTokenChanged() {
        listOf(SelfTestResult.PublishRejected(403), SelfTestResult.PublishFailed).forEach { result ->
            val detail = build(
                credentialProof = NtfyCredentialProof.STALE,
                selfTestStatus = SelfTestStatus.Done(result, 0L),
            ).byId(ReceiveSetupSteps.SELF_TEST_ID).statusDetail
            assertTrue(detail!!.contains("手順2〜5を確認してください"))
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
