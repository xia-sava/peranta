package to.sava.peranta.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 更新確認の実行状態と結果を UI へ公開する。起動時チェックと手動チェックの導線で共有する。
 * [UpdateChecker.check] は全ての失敗を [UpdateStatus.Failed] として返すため、ここでは例外を握らない。
 */
class UpdateController(
    private val checker: UpdateChecker,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow<UpdateStatus?>(null)

    /** 直近の更新確認結果。未確認は null。 */
    val status: StateFlow<UpdateStatus?> = _status.asStateFlow()

    private val _checking = MutableStateFlow(false)

    /** 更新確認の実行中フラグ。UI のボタン活性制御に使う。 */
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    /** 更新確認を実行する。実行中なら多重起動しない。 */
    fun checkNow() {
        if (_checking.value) {
            return
        }
        _checking.value = true
        scope.launch {
            try {
                _status.value = checker.check()
            } finally {
                _checking.value = false
            }
        }
    }
}
