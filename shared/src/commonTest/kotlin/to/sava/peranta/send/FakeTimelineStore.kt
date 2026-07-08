package to.sava.peranta.send

import to.sava.peranta.timeline.TimelineItem
import to.sava.peranta.timeline.TimelineStore

/** append されたアイテムを記録するだけのテスト用 [TimelineStore]。 */
class FakeTimelineStore : TimelineStore {
    val appended = mutableListOf<TimelineItem>()

    override suspend fun append(item: TimelineItem) {
        appended.add(item)
    }

    override suspend fun loadAll(): List<TimelineItem> = appended.toList()

    override suspend fun prune(maxItems: Int, now: Long) {}
}
