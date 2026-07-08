package to.sava.peranta.roster

import kotlinx.coroutines.test.runTest
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.PresencePayload
import to.sava.peranta.model.encodeEnvelope
import to.sava.peranta.net.NtfyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RosterStoreTest {

    private val keyBytes = generateKey()
    private val cipher = MessageCipher(keyBytes, "k1")
    private val controlTopic = "peranta-control-xyz"

    private fun presence(deviceId: String, endpoint: String, sentAt: Long) = PresencePayload(
        id = "p-$deviceId",
        from = deviceId,
        to = BROADCAST_TARGET,
        sentAtEpochMillis = sentAt,
        deviceName = deviceId,
        endpoint = endpoint,
        capabilities = listOf(CAPABILITY_DISPLAY),
    )

    private suspend fun event(payload: Payload): NtfyEvent =
        NtfyEvent(id = "e", time = payload.sentAtEpochMillis, topic = controlTopic, message = encodeEnvelope(cipher.seal(payload)))

    /** 履歴の presence を復号してロスターを構築する（最新採用込み）。 */
    @Test
    fun fetchBuildsRosterFromPresenceHistory() = runTest {
        val ntfy = RecordingControlNtfy(
            history = listOf(
                event(presence("dev-a", "https://h/a-old", sentAt = 100)),
                event(presence("dev-a", "https://h/a-new", sentAt = 200)),
                event(presence("dev-b", "https://h/b", sentAt = 150)),
            ),
        )
        val roster = (RosterStore(ntfy, cipher, controlTopic).fetch() as RosterFetchResult.Fetched).entries
        assertEquals(listOf("dev-a", "dev-b"), roster.map { it.deviceId })
        assertEquals("https://h/a-new", roster.first { it.deviceId == "dev-a" }.endpoint)
    }

    /** 復号できないメッセージ・presence でないメッセージは読み飛ばす。 */
    @Test
    fun fetchSkipsUndecodableAndNonPresenceMessages() = runTest {
        val notification = NotificationPayload(
            id = "n1", from = "dev-x", to = BROADCAST_TARGET, sentAtEpochMillis = 100,
            packageName = "p", appName = "a", title = "t", text = "b",
            notificationKey = "0|p|1|null|10", postedAtEpochMillis = 100,
        )
        val ntfy = RecordingControlNtfy(
            history = listOf(
                NtfyEvent("e", 100, controlTopic, "not-json-at-all"),
                event(notification),
                event(presence("dev-a", "https://h/a", sentAt = 100)),
            ),
        )
        val roster = (RosterStore(ntfy, cipher, controlTopic).fetch() as RosterFetchResult.Fetched).entries
        assertEquals(listOf("dev-a"), roster.map { it.deviceId })
    }

    /** 履歴取得が失敗したら、空ロスターではなく取得失敗として区別できる結果を返す。 */
    @Test
    fun fetchReturnsFetchFailedWhenHistoryThrows() = runTest {
        val ntfy = RecordingControlNtfy(historyError = RuntimeException("network down"))
        assertEquals(RosterFetchResult.FetchFailed, RosterStore(ntfy, cipher, controlTopic).fetch())
    }

    /** 履歴取得がタイムアウト時間枠を超えたら、ハングせず取得失敗として打ち切る。 */
    @Test
    fun fetchReturnsFetchFailedWhenHistoryTimesOut() = runTest {
        val ntfy = RecordingControlNtfy(
            history = listOf(event(presence("dev-a", "https://h/a", sentAt = 100))),
            historyDelayMillis = 1_000,
        )
        val store = RosterStore(ntfy, cipher, controlTopic, fetchTimeoutMillis = 100)
        assertEquals(RosterFetchResult.FetchFailed, store.fetch())
    }

    /** 履歴が本当に空（presence が 1 件もない）なら、取得失敗ではなく 0 件のロスターとして返す。 */
    @Test
    fun fetchReturnsFetchedEmptyWhenHistoryIsGenuinelyEmpty() = runTest {
        val ntfy = RecordingControlNtfy(history = emptyList())
        val result = RosterStore(ntfy, cipher, controlTopic).fetch()
        assertTrue((result as RosterFetchResult.Fetched).entries.isEmpty())
    }
}
