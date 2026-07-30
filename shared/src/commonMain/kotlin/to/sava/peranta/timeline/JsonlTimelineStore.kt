package to.sava.peranta.timeline

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import to.sava.peranta.model.PerantaJson
import to.sava.peranta.platform.ioDispatcher

/**
 * 1 行 1 JSON でタイムラインを保存する [TimelineStore] 実装。
 * 壊れた行は読み飛ばし、他の行を活かす。
 * ファイル I/O は [dispatcher] へ逃がし、UI スレッドを塞がない。
 */
class JsonlTimelineStore(
    private val file: TimelineFile,
    private val json: Json = PerantaJson,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
    private val log: Logger = Logger.withTag("TimelineStore"),
) : TimelineStore {

    private val mutex = Mutex()

    override suspend fun append(item: TimelineItem) {
        val line = json.encodeToString<TimelineItem>(item)
        withContext(dispatcher) {
            mutex.withLock { file.appendLine(line) }
        }
    }

    override suspend fun loadAll(): List<TimelineItem> =
        withContext(dispatcher) {
            mutex.withLock { readAllLocked() }
        }

    override suspend fun prune(maxItems: Int, now: Long, maxAgeMillis: Long?) {
        withContext(dispatcher) {
            mutex.withLock {
                val cutoff = maxAgeMillis?.let { now - it }
                val kept = readAllLocked()
                    .filter { it.expiresAtEpochMillis == null || it.expiresAtEpochMillis!! >= now }
                    .filter { cutoff == null || it.timestampEpochMillis >= cutoff }
                    .filterNot { it is ReceivedNotification && it.hiddenFromTimeline }
                    .takeLast(maxItems)
                file.overwrite(kept.map { json.encodeToString<TimelineItem>(it) })
            }
        }
    }

    private fun readAllLocked(): List<TimelineItem> =
        file.readLines().mapIndexedNotNull { index, line ->
            if (line.isBlank()) {
                null
            } else {
                try {
                    json.decodeFromString<TimelineItem>(line)
                } catch (e: SerializationException) {
                    log.w { "skipping corrupt timeline line ${index + 1} (${e::class.simpleName})" }
                    null
                }
            }
        }
}
