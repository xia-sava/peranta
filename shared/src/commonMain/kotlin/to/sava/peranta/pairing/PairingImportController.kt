package to.sava.peranta.pairing

import to.sava.peranta.config.ConfigRepository

/**
 * QR スキャンや手動貼り付けで受け取ったペアリング文字列を復号して設定へ適用する（§10.3）。
 * 取り込み画面から復号・適用ロジックを切り離し、結果を [PairingImportResult] で返す。
 * カメラ起動などプラットフォーム依存の入力手段には依存せず、生文字列だけを受け取る。
 * [devMode] は [PairingApplier] の TLS 強制可否へそのまま渡す（§16）。
 */
class PairingImportController(configRepository: ConfigRepository, devMode: Boolean = false) {

    private val applier: PairingApplier = PairingApplier(configRepository, devMode)

    /**
     * [rawUri] を復号し、成功なら設定へ適用する。
     * 前後の空白は入力ミスとして除去する。失敗理由は握り潰さず文言で返す。
     */
    fun import(rawUri: String): PairingImportResult =
        when (val result = PairingUri.decode(rawUri.trim())) {
            is PairingResult.Success -> {
                applier.apply(result.data)
                PairingImportResult.Applied(result.data.keyId)
            }

            is PairingResult.Failure -> PairingImportResult.Failed(result.error.reason)
        }
}

/** [PairingImportController.import] の結果。 */
sealed class PairingImportResult {

    /** 復号・適用に成功した。[keyId] は取り込んだ共有鍵の識別子。 */
    class Applied(val keyId: String) : PairingImportResult()

    /** 復号に失敗した。[reason] は表示用の失敗理由。 */
    class Failed(val reason: String) : PairingImportResult()
}
