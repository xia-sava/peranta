package to.sava.peranta.timeline

/** メモリ上に行を保持するテスト用 [TimelineFile]。 */
class FakeTimelineFile(initial: List<String> = emptyList()) : TimelineFile {

    private val lines = initial.toMutableList()

    override fun readLines(): List<String> = lines.toList()

    override fun appendLine(line: String) {
        lines.add(line)
    }

    override fun overwrite(lines: List<String>) {
        this.lines.clear()
        this.lines.addAll(lines)
    }
}
