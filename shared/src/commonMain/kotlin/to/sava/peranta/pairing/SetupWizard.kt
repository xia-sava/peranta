package to.sava.peranta.pairing

import to.sava.peranta.config.PerantaConfig

/** 初期設定ウィザードのステップ（§10.2、§10.3）。 */
enum class SetupStep {
    /** サーバ接続（host / token / port）を入力する。 */
    CONNECTION,

    /** 端末名を入力する。 */
    DEVICE,

    /** 共有鍵を生成する。 */
    KEY,

    /** ペアリング（QR の表示または取り込み）を行う。 */
    PAIRING,
}

/**
 * 初期設定ウィザードのロール。
 *
 * [SENDER] は設定元の端末で、接続・端末名・鍵を自ら用意し、最後に QR を表示して他端末へ配布する。
 * [RECEIVER] は受信専用の端末で、接続・共有鍵を QR から取り込むため CONNECTION/KEY を持たず、
 * PAIRING（取り込み）から始めて端末名を設定する。
 */
enum class SetupRole {
    SENDER,
    RECEIVER,
}

/**
 * ロール別にステップ列を定義し、ステップの完了判定と前後移動を提供する（§10.2、§10.3）。
 * 永続化には関与せず、渡された [PerantaConfig] の状態だけで判定する。
 */
object SetupWizard {

    /** [role] が踏むステップ列を順序どおりに返す。 */
    fun steps(role: SetupRole): List<SetupStep> =
        when (role) {
            SetupRole.SENDER -> listOf(
                SetupStep.CONNECTION,
                SetupStep.DEVICE,
                SetupStep.KEY,
                SetupStep.PAIRING,
            )

            SetupRole.RECEIVER -> listOf(
                SetupStep.PAIRING,
                SetupStep.DEVICE,
            )
        }

    /**
     * [step] の完了条件を [config] が満たしているか。
     * PAIRING は [role] によって完了条件が異なる: SENDER は QR 表示まで済んでいる必要があるため
     * [PerantaConfig.isReadyForSend]（controlTopic の採番を含む）で判定し、
     * RECEIVER は QR 取り込みによる共有鍵の有無だけで判定する。
     */
    fun canProceed(step: SetupStep, config: PerantaConfig, role: SetupRole): Boolean =
        when (step) {
            SetupStep.CONNECTION -> config.host.isNotBlank() && !config.accessToken.isNullOrBlank()
            SetupStep.DEVICE -> !config.deviceName.isNullOrBlank()
            SetupStep.KEY -> config.hasSharedKey
            SetupStep.PAIRING -> when (role) {
                SetupRole.SENDER -> config.isReadyForSend
                SetupRole.RECEIVER -> config.hasSharedKey
            }
        }

    /**
     * [role] のステップ列で最初の未完了ステップを返す。全て完了していれば null。
     */
    fun firstIncompleteStep(config: PerantaConfig, role: SetupRole): SetupStep? =
        steps(role).firstOrNull { !canProceed(it, config, role) }

    /** [role] のステップ列で [step] の次のステップを返す。末尾・列外なら null。 */
    fun next(step: SetupStep, role: SetupRole): SetupStep? {
        val ordered = steps(role)
        val index = ordered.indexOf(step)
        if (index < 0 || index == ordered.lastIndex) return null
        return ordered[index + 1]
    }

    /** [role] のステップ列で [step] の前のステップを返す。先頭・列外なら null。 */
    fun previous(step: SetupStep, role: SetupRole): SetupStep? {
        val ordered = steps(role)
        val index = ordered.indexOf(step)
        if (index <= 0) return null
        return ordered[index - 1]
    }
}
