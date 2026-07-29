package to.sava.peranta.roster

import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.PresencePayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RosterTest {

    private fun presence(
        deviceId: String,
        deviceName: String,
        endpoint: String,
        sentAt: Long,
        sender: Boolean = false,
    ) = PresencePayload(
        id = "p-$deviceId-$sentAt",
        from = deviceId,
        to = BROADCAST_TARGET,
        sentAtEpochMillis = sentAt,
        deviceName = deviceName,
        endpoint = endpoint,
        capabilities = listOf(CAPABILITY_DISPLAY),
        sender = sender,
    )

    /** 同じ deviceId の presence が複数あれば送信時刻が最新のものを採用する。 */
    @Test
    fun latestPresencePerDeviceWins() {
        val roster = buildRoster(
            listOf(
                presence("dev-a", "Old Name", "https://h/old", sentAt = 100),
                presence("dev-a", "New Name", "https://h/new", sentAt = 300),
                presence("dev-a", "Mid Name", "https://h/mid", sentAt = 200),
            ),
        )
        val entry = roster.single()
        assertEquals("dev-a", entry.deviceId)
        assertEquals("New Name", entry.deviceName)
        assertEquals("https://h/new", entry.endpoint)
        assertEquals(300, entry.lastUpdatedEpochMillis)
    }

    /** 複数端末は deviceId 昇順で安定して並ぶ。 */
    @Test
    fun entriesAreSortedByDeviceId() {
        val roster = buildRoster(
            listOf(
                presence("dev-c", "C", "https://h/c", sentAt = 100),
                presence("dev-a", "A", "https://h/a", sentAt = 100),
                presence("dev-b", "B", "https://h/b", sentAt = 100),
            ),
        )
        assertEquals(listOf("dev-a", "dev-b", "dev-c"), roster.map { it.deviceId })
    }

    /** エンドポイント URL から末尾パスセグメントを topic として取り出す。 */
    @Test
    fun topicOfExtractsLastPathSegment() {
        assertEquals("UPabc123", topicOf("https://peranta.example.com/UPabc123"))
        assertEquals("peranta-dev-desk-xyz", topicOf("http://localhost:8090/peranta-dev-desk-xyz"))
        assertEquals("t", topicOf("https://h/t/"))
    }

    /** fan-out は自分を除いた全端末のエンドポイント topic を返す。 */
    @Test
    fun resolveExcludesSelfAndMapsToTopics() {
        val roster = buildRoster(
            listOf(
                presence("self", "Me", "https://h/self-topic", sentAt = 100),
                presence("other-1", "One", "https://h/topic-1", sentAt = 100),
                presence("other-2", "Two", "https://h/topic-2", sentAt = 100),
            ),
        )
        val targets = resolveDeliveryTargets(roster, selfDeviceId = "self", fallback = listOf("static"))
        assertEquals(listOf("topic-1", "topic-2"), targets)
    }

    /** ロスターから宛先が得られないときは静的な配送先へ退避する（deliveryTopics フォールバック）。 */
    @Test
    fun resolveFallsBackWhenRosterHasNoOtherDevices() {
        val onlySelf = buildRoster(listOf(presence("self", "Me", "https://h/self", sentAt = 100)))
        assertEquals(listOf("static-a", "static-b"), resolveDeliveryTargets(onlySelf, "self", listOf("static-a", "static-b")))
        assertEquals(listOf("static-a"), resolveDeliveryTargets(emptyList(), "self", listOf("static-a")))
    }

    /** 取得できたロスターが空なら [resolveDeliveryTargets] と同じくフォールバックへ退避する。 */
    @Test
    fun resolveDeliveryTopicsFallsBackWhenFetchedRosterEmpty() {
        val topics = resolveDeliveryTopics(
            RosterFetchResult.Fetched(emptyList()),
            selfDeviceId = "self",
            fallback = listOf("static-a"),
        )
        assertEquals(listOf("static-a"), topics)
    }

    /**
     * 履歴取得自体が失敗した（[RosterFetchResult.FetchFailed]）ときは、フォールバックが
     * 設定されていても使わせず解決不能（空）を返す。取得失敗と「本当に空」を区別するため。
     */
    @Test
    fun resolveDeliveryTopicsIgnoresFallbackWhenFetchFailed() {
        val topics = resolveDeliveryTopics(
            RosterFetchResult.FetchFailed,
            selfDeviceId = "self",
            fallback = listOf("static-a"),
        )
        assertEquals(emptyList(), topics)
    }

    /** 一点指定コマンド（§3.4）の宛先解決は、対象 deviceId のエンドポイント topic を引く。 */
    @Test
    fun resolveTargetTopicFindsSingleDeviceEndpoint() {
        val roster = buildRoster(
            listOf(
                presence("phone", "Phone", "https://h/phone-topic", sentAt = 100),
                presence("tablet", "Tablet", "https://h/tablet-topic", sentAt = 100),
            ),
        )
        assertEquals("phone-topic", resolveTargetTopic(roster, "phone"))
    }

    /** 対象 deviceId がロスターに居なければ null（宛先が引けずコマンドは送れない）。 */
    @Test
    fun resolveTargetTopicReturnsNullForUnknownDevice() {
        val roster = buildRoster(listOf(presence("phone", "Phone", "https://h/phone-topic", sentAt = 100)))
        assertNull(resolveTargetTopic(roster, "tablet"))
    }

    /** fetch 結果からの一点指定解決: 取得成功なら引け、取得失敗なら null。 */
    @Test
    fun resolveTargetTopicFromFetchResult() {
        val fetched = RosterFetchResult.Fetched(
            buildRoster(listOf(presence("phone", "Phone", "https://h/phone-topic", sentAt = 100))),
        )
        assertEquals("phone-topic", resolveTargetTopic(fetched, "phone"))
        assertNull(resolveTargetTopic(RosterFetchResult.FetchFailed, "phone"))
    }
}
