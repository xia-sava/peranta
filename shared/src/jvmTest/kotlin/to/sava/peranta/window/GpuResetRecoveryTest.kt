package to.sava.peranta.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

/** `DXGI_ERROR_DEVICE_RESET`。TDR の後に `GetDeviceRemovedReason` が返す。 */
private const val DEVICE_RESET = 0x887A0007.toInt()

/** 起きたことを順に書き留める。 */
private class Journal {
    val entries = mutableListOf<String>()

    fun sleep(duration: Duration) {
        entries += "sleep $duration"
    }
}

/** [reasons] を順に返し、尽きたら最後の値を返し続ける見張りのデバイス。 */
private class FakeProbe(
    override val adapterName: String,
    private val journal: Journal,
    private vararg val reasons: Int,
) : GpuProbe {
    private var polled = 0

    override fun removedReason(): Int = reasons[minOf(polled++, reasons.lastIndex)]

    override fun release() {
        journal.entries += "release $adapterName"
    }
}

class GpuResetRecoveryTest {

    private val journal = Journal()

    private val replacements = listOf(FakeProbe("replacement", journal, 0))

    private fun recovery(
        openProbes: () -> List<GpuProbe> = {
            journal.entries += "open"
            replacements
        },
    ) = GpuResetRecovery(
        openProbes = openProbes,
        recreateRenderers = { journal.entries += "recreate" },
        sleep = journal::sleep,
    )

    /**
     * 失われた見張りのデバイスは、Skiko に作り直させ、新しい見張りのデバイスを作り、[PROBE_RELEASE_DELAY] 待ってから
     * 解放する。新しいデバイスを作る前に解放するとプロセスごと落ちるため、この順序が要る。
     */
    @Test
    fun `releases the lost probes only after replacements are opened`() {
        val probes = listOf(FakeProbe("GPU", journal, DEVICE_RESET))

        recovery().recoverFromNextReset(probes)

        assertEquals(
            listOf("sleep $RENDERER_RECREATION_DELAY", "recreate", "open", "sleep $PROBE_RELEASE_DELAY", "release GPU"),
            journal.entries,
        )
    }

    /** 立て直した後は、新しく作った見張りのデバイスで次の TDR を待つ。 */
    @Test
    fun `hands over the replacements for the next reset`() {
        val probes = listOf(FakeProbe("GPU", journal, DEVICE_RESET))

        assertEquals(replacements, recovery().recoverFromNextReset(probes))
    }

    /** どれかが失われるまで [GPU_PROBE_POLL_INTERVAL] ごとに見に行き、その間は何も作り直さない。 */
    @Test
    fun `keeps polling until a probe is lost`() {
        val probes = listOf(FakeProbe("GPU", journal, 0, 0, DEVICE_RESET))

        recovery().recoverFromNextReset(probes)

        assertEquals(
            listOf("sleep $GPU_PROBE_POLL_INTERVAL", "sleep $GPU_PROBE_POLL_INTERVAL", "sleep $RENDERER_RECREATION_DELAY"),
            journal.entries.takeWhile { it != "recreate" },
        )
    }

    /** 失われたのが 1 つの GPU だけでも、見張りのデバイスは全て解放する。新しい見張りのデバイスは全ての GPU に作るため。 */
    @Test
    fun `releases every probe even when only one is lost`() {
        val probes = listOf(FakeProbe("lost", journal, DEVICE_RESET), FakeProbe("intact", journal, 0))

        recovery().recoverFromNextReset(probes)

        assertEquals(listOf("release lost", "release intact"), journal.entries.filter { it.startsWith("release") })
    }

    /** リセットの直後に見張りのデバイスを作れなければ、[GPU_PROBE_POLL_INTERVAL] 空けて作れるまで試す。 */
    @Test
    fun `retries opening the probes until they can be opened`() {
        val opened = listOf(FakeProbe("GPU", journal, 0))
        var attempts = 0
        val recovery = recovery(openProbes = {
            if (attempts++ < 2) throw GpuProbeUnavailable("not ready")
            opened
        })

        val probes = recovery.openProbesWhenAvailable()

        assertEquals(opened, probes)
        assertEquals(listOf("sleep $GPU_PROBE_POLL_INTERVAL", "sleep $GPU_PROBE_POLL_INTERVAL"), journal.entries)
    }

    /** 新しい見張りのデバイスを作れるまでは、失われた見張りのデバイスを解放しない。 */
    @Test
    fun `holds the lost probes until replacements can be opened`() {
        val probes = listOf(FakeProbe("GPU", journal, DEVICE_RESET))
        var attempts = 0
        val recovery = recovery(openProbes = {
            journal.entries += "open"
            if (attempts++ < 1) throw GpuProbeUnavailable("no GPU could be watched")
            replacements
        })

        recovery.recoverFromNextReset(probes)

        assertEquals(
            listOf("open", "sleep $GPU_PROBE_POLL_INTERVAL", "open", "sleep $PROBE_RELEASE_DELAY", "release GPU"),
            journal.entries.dropWhile { it != "open" },
        )
    }
}
