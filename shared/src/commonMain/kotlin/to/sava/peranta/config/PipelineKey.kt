package to.sava.peranta.config

/**
 * 受信パイプラインの再構築要否を判定する比較キー。
 * これらの値のいずれかが変化したときに、古いパイプラインを畳んで作り直す。
 *
 * 鍵が変わったことを [keyId] だけでは判別できないため（設定を消して同じ [keyId] のまま別の鍵で
 * ペアリングし直せる）、比較には [sharedKeyBase64] そのものを含める。
 * 一方でこの値は文字列化してはならないため、[toString] では伏せる。
 */
data class PipelineKey(
    val keyId: String?,
    val sharedKeyBase64: String?,
    val sendEnabled: Boolean,
    val persistSensitiveHistory: Boolean,
    val deviceId: String?,
) {
    override fun toString(): String =
        "PipelineKey(keyId=$keyId, sharedKeyBase64=***, sendEnabled=$sendEnabled, " +
            "persistSensitiveHistory=$persistSensitiveHistory, deviceId=$deviceId)"
}

/** [PerantaConfig] からパイプライン比較用の [PipelineKey] を抽出する。 */
fun PerantaConfig.toPipelineKey(): PipelineKey =
    PipelineKey(
        keyId = keyId,
        sharedKeyBase64 = sharedKeyBase64,
        sendEnabled = sendEnabled,
        persistSensitiveHistory = persistSensitiveHistory,
        deviceId = deviceId,
    )
