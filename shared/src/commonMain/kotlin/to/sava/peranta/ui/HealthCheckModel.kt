package to.sava.peranta.ui

/**
 * 健康診断（§10.5）の 1 項目の状態。
 * [PASS] は要件を満たす合格、[FAILING] は未達で「直す」導線を持つ不合格、
 * [INFO] は合否を出さず案内文だけを示す情報項目（OEM 省電力・非対応環境など）、
 * [NOT_APPLICABLE] は現在の設定では対象外で画面に出さない項目を表す。
 */
enum class HealthCheckState {
    PASS,
    FAILING,
    INFO,
    NOT_APPLICABLE,
}

/**
 * 健康診断の 1 項目（§10.5）。
 * [detail] は状態の補足や「どこを直すか」の案内文で、無ければ null。
 * [fixLabel] と [onFix] は「直す」導線で、項目ごとに文言（権限を許可 / 設定を開く / 登録する 等）を変える。
 * 両方が揃ったときだけ操作ボタンを出す。合格・情報項目や、直す手段が無い項目では null にする。
 */
data class HealthCheckItem(
    val id: String,
    val label: String,
    val state: HealthCheckState,
    val detail: String? = null,
    val fixLabel: String? = null,
    val onFix: (() -> Unit)? = null,
)

/**
 * プラットフォーム依存のチェックを実行して健康診断の項目一覧を返す（§10.5）。
 * PackageManager / UnifiedPush / PowerManager やレジストリなどの実アクセスは各プラットフォームへ隔離し、
 * commonMain の画面と集計・遷移判定は結果の [HealthCheckItem] だけを扱う。
 */
fun interface HealthChecker {
    suspend fun check(): List<HealthCheckItem>
}

/**
 * 健康診断の結果に、利用者の対処を要する不合格項目が 1 つでもあるか（§10.5）。
 * 情報項目（[HealthCheckState.INFO]）や対象外項目（[HealthCheckState.NOT_APPLICABLE]）は対処不要とみなす。
 * 起動時にこれが真なら健康診断画面へ自動遷移する判定に使う。
 */
fun healthCheckNeedsAttention(items: List<HealthCheckItem>): Boolean =
    items.any { it.state == HealthCheckState.FAILING }

/** 画面見出しのタグ。 */
const val TAG_HEALTH_TITLE: String = "health-title"

/** 「今すぐ再チェック」ボタンのタグ。 */
const val TAG_HEALTH_RECHECK: String = "health-recheck"

/** チェック実行中のインジケータのタグ。 */
const val TAG_HEALTH_LOADING: String = "health-loading"

/** 全項目が合格・対象外のときに出す案内文のタグ。 */
const val TAG_HEALTH_ALL_CLEAR: String = "health-all-clear"

/** 戻るボタンのタグ。 */
const val TAG_HEALTH_BACK: String = "health-back"

/** 項目行の状態マーカーのタグ接頭辞（末尾に item id を付ける）。 */
const val TAG_HEALTH_STATE_PREFIX: String = "health-state-"

/** 項目行の「直す」ボタンのタグ接頭辞（末尾に item id を付ける）。 */
const val TAG_HEALTH_FIX_PREFIX: String = "health-fix-"

/** 「直す」操作が失敗したときのエラー文のタグ接頭辞（末尾に item id を付ける）。 */
const val TAG_HEALTH_FIX_ERROR_PREFIX: String = "health-fix-error-"
