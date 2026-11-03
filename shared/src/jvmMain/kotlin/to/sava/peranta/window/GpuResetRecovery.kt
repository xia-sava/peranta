package to.sava.peranta.window

import co.touchlab.kermit.Logger
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Skiko が描画 API を受け取るシステムプロパティ。 */
private const val SKIKO_RENDER_API_PROPERTY = "skiko.renderApi"

/** Skiko が描画 API を受け取る環境変数。 */
private const val SKIKO_RENDER_API_ENVIRONMENT = "SKIKO_RENDER_API"

/** GPU のリセットから立て直せないときに使う描画 API。GPU を使わないため、リセットの影響を受けない。 */
private const val SOFTWARE_RENDER_API = "SOFTWARE"

/** 見張りのデバイスが失われていないかを見に行く間隔。 */
internal val GPU_PROBE_POLL_INTERVAL = 1.seconds

/** デバイスが失われたと分かってから、Skiko に作り直させるまでの間。リセットの途中で作り直さないよう待つ。 */
internal val RENDERER_RECREATION_DELAY = 1.seconds

/** 新しい見張りのデバイスを作ってから、失われた見張りのデバイスを解放するまでに空ける間。 */
internal val PROBE_RELEASE_DELAY = 2.seconds

private val log = Logger.withTag("GpuReset")

/** 1 つの GPU の上に作った、そのデバイスが失われたかを見張るためのもの。 */
internal interface GpuProbe {
    val adapterName: String

    /** デバイスが失われていれば、その理由の `HRESULT`。失われていなければ 0。 */
    fun removedReason(): Int

    fun release()
}

/**
 * GPU のリセット（TDR）から描画を立て直す（§3.3）。
 *
 * Skiko は TDR でデバイスを失っても例外にせず、ネイティブ側で `Failed Direct3D call` を標準出力へ書くだけで
 * 描くのをやめる。ウィンドウは応答したまま中身が真っ白になる。そのため Skiko の外で GPU ごとに見張りの
 * デバイスを持ち、それが失われたら Skiko に作り直させる。
 *
 * TDR はその GPU の上のデバイスを全て失わせるため、見張りのデバイスも Skiko のものと同時に失われる。
 *
 * **失われた見張りのデバイスは、新しい見張りのデバイスを作ってから解放する。** 生きている D3D11 のデバイスが
 * プロセスに無いうちに解放すると、プロセスごと落ちる（`EXCEPTION_ACCESS_VIOLATION`）。
 *
 * [openProbes] は見張りのデバイスを 1 つ以上作って返す。1 つも作れなければ [GpuProbeUnavailable] を投げる。
 */
internal class GpuResetRecovery(
    private val openProbes: () -> List<GpuProbe>,
    private val recreateRenderers: () -> Unit,
    private val sleep: (Duration) -> Unit = { Thread.sleep(it.inWholeMilliseconds) },
) {
    /** TDR を 1 回待ち、描画を作り直し、失われた [probes] を新しい見張りのデバイスと入れ替えて返す。 */
    fun recoverFromNextReset(probes: List<GpuProbe>): List<GpuProbe> {
        val removed = awaitRemoval(probes)
        log.w { "GPU reset: " + removed.joinToString { (probe, reason) -> "${probe.adapterName} ${hresult(reason)}" } }
        sleep(RENDERER_RECREATION_DELAY)
        recreateRenderers()
        val replacements = openProbesWhenAvailable()
        sleep(PROBE_RELEASE_DELAY)
        probes.forEach(GpuProbe::release)
        log.i { "released the probes lost in the GPU reset" }
        return replacements
    }

    /** 見張りのデバイスを作れるまで試し続ける。リセットの直後は作れないことがある。 */
    fun openProbesWhenAvailable(): List<GpuProbe> {
        while (true) {
            try {
                return openProbes()
            } catch (unavailable: GpuProbeUnavailable) {
                log.w { "could not watch the GPU for a reset: ${unavailable.message}" }
                sleep(GPU_PROBE_POLL_INTERVAL)
            }
        }
    }

    /** [probes] のどれかが失われるまで待ち、失われたものと理由を返す。 */
    private fun awaitRemoval(probes: List<GpuProbe>): List<Pair<GpuProbe, Int>> {
        while (true) {
            val removed = probes.map { it to it.removedReason() }.filter { (_, reason) -> reason != 0 }
            if (removed.isNotEmpty()) return removed
            sleep(GPU_PROBE_POLL_INTERVAL)
        }
    }

    /** [first] から見張り始め、以後 TDR のたびに立て直しては見張り直す。 */
    fun run(first: List<GpuProbe>): Nothing {
        var probes = first
        while (true) {
            probes = recoverFromNextReset(probes)
        }
    }
}

/**
 * Windows で、GPU のリセットから描画を立て直す見張りを始める（§3.3）。**ウィンドウを作るより前に呼ぶ。**
 *
 * 立て直せないとき（Skiko の作り直す入口に手が届かない、見張りのデバイスを作れない）は、描画 API を
 * [SOFTWARE_RENDER_API] にする。リセットのたびに真っ白で止まるより、GPU を使わずに描く方を選ぶ。
 *
 * 描画 API を [SKIKO_RENDER_API_ENVIRONMENT] か [SKIKO_RENDER_API_PROPERTY] で指定されていれば、それに従い何もしない。
 */
fun installGpuResetRecovery() {
    if (!System.getProperty("os.name").startsWith("Windows")) return
    val specified = System.getenv(SKIKO_RENDER_API_ENVIRONMENT) ?: System.getProperty(SKIKO_RENDER_API_PROPERTY)
    if (specified != null) {
        log.i { "not watching the GPU for a reset: the render API is specified as $specified" }
        return
    }
    if (!SkikoRendererReset.isAvailable()) return renderWithoutGpu("cannot recreate the renderer of Skiko")

    val probes = try {
        openD3D11Probes()
    } catch (unavailable: GpuProbeUnavailable) {
        return renderWithoutGpu("cannot watch the GPU: ${unavailable.message}")
    }
    val recovery = GpuResetRecovery(::openD3D11Probes, SkikoRendererReset::recreateAll)
    thread(isDaemon = true, name = "gpu-reset-recovery") {
        try {
            recovery.run(probes)
        } catch (failed: Throwable) {
            log.e(failed) { "stopped watching the GPU for a reset" }
            throw failed
        }
    }
}

private fun renderWithoutGpu(reason: String) {
    log.w { "rendering without the GPU: $reason" }
    System.setProperty(SKIKO_RENDER_API_PROPERTY, SOFTWARE_RENDER_API)
}
