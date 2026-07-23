package to.sava.peranta.ui.shell

import to.sava.peranta.roster.RosterEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class RosterDisplayOrderTest {

    private fun entry(deviceId: String, deviceName: String) = RosterEntry(
        deviceId = deviceId,
        deviceName = deviceName,
        endpoint = "https://h/$deviceId",
        capabilities = emptyList(),
        sender = false,
        lastUpdatedEpochMillis = 0L,
    )

    /** 自端末は deviceName の順序に関わらず先頭に来る。 */
    @Test
    fun selfDeviceComesFirst() {
        val entries = listOf(entry("dev-b", "Bravo"), entry("dev-a", "Alpha"), entry("dev-c", "Charlie"))
        val ordered = rosterDisplayOrder(entries, selfDeviceId = "dev-c")
        assertEquals("dev-c", ordered.first().deviceId)
    }

    /** 自端末以外は deviceName 昇順に並ぶ。 */
    @Test
    fun othersAreSortedByDeviceNameAscending() {
        val entries = listOf(entry("dev-b", "Bravo"), entry("dev-a", "Alpha"), entry("dev-c", "Charlie"))
        val ordered = rosterDisplayOrder(entries, selfDeviceId = null)
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), ordered.map { it.deviceName })
    }

    /** deviceName が同名のときは deviceId 昇順で安定した並びにする。 */
    @Test
    fun sameDeviceNameIsStableByDeviceId() {
        val entries = listOf(entry("dev-z", "Same"), entry("dev-a", "Same"))
        val ordered = rosterDisplayOrder(entries, selfDeviceId = null)
        assertEquals(listOf("dev-a", "dev-z"), ordered.map { it.deviceId })
    }

    /** selfDeviceId が null でも壊れず deviceName 昇順で並ぶ。 */
    @Test
    fun nullSelfDeviceIdOrdersByDeviceNameOnly() {
        val entries = listOf(entry("dev-b", "Bravo"), entry("dev-a", "Alpha"))
        val ordered = rosterDisplayOrder(entries, selfDeviceId = null)
        assertEquals(listOf("dev-a", "dev-b"), ordered.map { it.deviceId })
    }

    /** selfDeviceId が一覧に存在しなくても壊れず deviceName 昇順で並ぶ。 */
    @Test
    fun absentSelfDeviceIdOrdersByDeviceNameOnly() {
        val entries = listOf(entry("dev-b", "Bravo"), entry("dev-a", "Alpha"))
        val ordered = rosterDisplayOrder(entries, selfDeviceId = "dev-unknown")
        assertEquals(listOf("dev-a", "dev-b"), ordered.map { it.deviceId })
    }
}
