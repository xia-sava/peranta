package to.sava.peranta.window

import co.touchlab.kermit.Logger
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid
import com.sun.jna.ptr.PointerByReference

private val IID_IDXGI_FACTORY1 = Guid.GUID.fromString("{770aae78-f26f-4dba-a829-253c83d1b387}")

private const val S_OK = 0
private const val DXGI_ERROR_NOT_FOUND = 0x887A0002.toInt()
private const val DXGI_ADAPTER_FLAG_SOFTWARE = 2
private const val D3D11_SDK_VERSION = 7

/** `DXGI_ADAPTER_DESC1` の大きさ。名前の `WCHAR[128]` に始まり、末尾の `Flags` までを 8 バイト境界に揃えたもの。 */
private const val ADAPTER_DESC1_SIZE = 312L

/** `DXGI_ADAPTER_DESC1` の `Flags` の位置。 */
private const val ADAPTER_DESC1_FLAGS_OFFSET = 304L

/** `IUnknown::Release` の vtable での位置。 */
private const val UNKNOWN_RELEASE = 2

/** `IDXGIFactory1::EnumAdapters1` の vtable での位置。 */
private const val FACTORY1_ENUM_ADAPTERS1 = 12

/** `IDXGIAdapter1::GetDesc1` の vtable での位置。 */
private const val ADAPTER1_GET_DESC1 = 10

/** `ID3D11Device::GetDeviceRemovedReason` の vtable での位置。 */
private const val DEVICE_GET_DEVICE_REMOVED_REASON = 39

private val log = Logger.withTag("GpuReset")

/** 見張りに使うデバイスを作れなかった。 */
internal class GpuProbeUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

/** COM のオブジェクトの vtable の [index] 番目のメソッドを呼び、戻り値の `HRESULT` を返す。 */
private fun Pointer.callMethod(index: Int, vararg args: Any?): Int {
    val table = getPointer(0)
    val method = Function.getFunction(table.getPointer(index.toLong() * Native.POINTER_SIZE))
    return method.invokeInt(arrayOf(this, *args))
}

private fun Pointer.release() {
    callMethod(UNKNOWN_RELEASE)
}

/** 1 つの GPU の上に作った、何も描かない D3D11 のデバイス。 */
private class D3D11Probe(override val adapterName: String, private val device: Pointer) : GpuProbe {
    override fun removedReason(): Int = device.callMethod(DEVICE_GET_DEVICE_REMOVED_REASON)

    override fun release() = device.release()
}

/**
 * ソフトウェアのものを除く全ての GPU に、見張りに使う D3D11 のデバイスを 1 つずつ作る。
 *
 * 作れない GPU は飛ばしてログに残す。一覧を取れないとき、1 つも作れなかったときは [GpuProbeUnavailable] を投げる。
 */
internal fun openD3D11Probes(): List<GpuProbe> {
    val (dxgi, d3d11) = try {
        NativeLibrary.getInstance("dxgi") to NativeLibrary.getInstance("d3d11")
    } catch (missing: UnsatisfiedLinkError) {
        throw GpuProbeUnavailable("DirectX is not available", missing)
    }

    val factoryRef = PointerByReference()
    val created = dxgi.getFunction("CreateDXGIFactory1").invokeInt(arrayOf(IID_IDXGI_FACTORY1, factoryRef))
    if (created != S_OK) throw GpuProbeUnavailable("CreateDXGIFactory1 failed: ${hresult(created)}")
    val factory = factoryRef.value

    try {
        return generateSequence(0) { it + 1 }
            .map { index -> PointerByReference().let { factory.callMethod(FACTORY1_ENUM_ADAPTERS1, index, it) to it } }
            .takeWhile { (enumerated, _) -> enumerated != DXGI_ERROR_NOT_FOUND }
            .map { (enumerated, adapterRef) ->
                if (enumerated != S_OK) throw GpuProbeUnavailable("EnumAdapters1 failed: ${hresult(enumerated)}")
                adapterRef.value
            }
            .mapNotNull { adapter ->
                try {
                    openProbe(d3d11, adapter)
                } finally {
                    adapter.release()
                }
            }
            .toList()
            .ifEmpty { throw GpuProbeUnavailable("no GPU could be watched") }
    } finally {
        factory.release()
    }
}

/** [adapter] がソフトウェアのものでなければ、その上にデバイスを作る。 */
private fun openProbe(d3d11: NativeLibrary, adapter: Pointer): GpuProbe? {
    val desc = Memory(ADAPTER_DESC1_SIZE).apply { clear() }
    val described = adapter.callMethod(ADAPTER1_GET_DESC1, desc)
    if (described != S_OK) {
        log.w { "could not describe a GPU to watch for a reset: ${hresult(described)}" }
        return null
    }
    if (desc.getInt(ADAPTER_DESC1_FLAGS_OFFSET) and DXGI_ADAPTER_FLAG_SOFTWARE != 0) return null
    val name = desc.getWideString(0)

    val deviceRef = PointerByReference()
    // アダプタを指定するときは、ドライバの種類を `D3D_DRIVER_TYPE_UNKNOWN`（0）にする決まり。
    val created = d3d11.getFunction("D3D11CreateDevice").invokeInt(
        arrayOf(adapter, 0, null, 0, null, 0, D3D11_SDK_VERSION, deviceRef, null, null)
    )
    if (created != S_OK) {
        log.w { "could not watch $name for a GPU reset: ${hresult(created)}" }
        return null
    }
    return D3D11Probe(name, deviceRef.value)
}

internal fun hresult(code: Int): String = "0x%08x".format(code)
