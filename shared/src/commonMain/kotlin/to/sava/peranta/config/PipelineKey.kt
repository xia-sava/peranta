package to.sava.peranta.config

/**
 * 受信パイプラインの再構築要否を判定する比較キー。
 * これらの値のいずれかが変化したときに、古いパイプラインを畳んで作り直す。
 */
data class PipelineKey(
    val keyId: String?,
    val sharedKeyBase64: String?,
    val sendEnabled: Boolean,
    val persistSensitiveHistory: Boolean,
    val deviceId: String?,
)

/** [PerantaConfig] からパイプライン比較用の [PipelineKey] を抽出する。 */
fun PerantaConfig.toPipelineKey(): PipelineKey =
    PipelineKey(
        keyId = keyId,
        sharedKeyBase64 = sharedKeyBase64,
        sendEnabled = sendEnabled,
        persistSensitiveHistory = persistSensitiveHistory,
        deviceId = deviceId,
    )
