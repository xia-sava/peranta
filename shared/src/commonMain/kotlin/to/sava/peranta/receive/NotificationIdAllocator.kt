package to.sava.peranta.receive

import com.russhwolf.settings.Settings

/**
 * payload.id とローカル通知 ID（Int）の対応表（§3.4）。
 * 既読同期で他端末から消された通知を OS 通知としても取り下げられるよう、
 * 同じ payload.id には常に同じ通知 ID を割り当てて永続化する。
 * コマンド受信の配線は M8 だが、対応表の土台をここで用意する。
 * 複数スレッドから呼ばれても対応表が壊れないよう [idFor] は排他制御する。
 * プロセス内では単一インスタンスを共有して通知 ID の衝突を避ける。
 */
class NotificationIdAllocator(
    private val settings: Settings,
    private val capacity: Int = DEFAULT_CAPACITY,
) {

    /**
     * [payloadId] に対応する通知 ID を返す。未知の id には次の連番を割り当てて記録する。
     * 同じ id を再度渡すと同じ通知 ID を返す。対応表が上限を超えたら古い割り当てを剪定する。
     */
    @Synchronized
    fun idFor(payloadId: String): Int {
        settings.getIntOrNull(keyFor(payloadId))?.let { return it }
        val assigned = settings.getInt(KEY_SEQUENCE, FIRST_ID)
        settings.putInt(keyFor(payloadId), assigned)
        settings.putInt(KEY_SEQUENCE, assigned + 1)
        pruneToCapacity()
        return assigned
    }

    /** 対応表が [capacity] を超えたら、割り当て番号が小さい（＝古い）ものから FIFO で取り除く。 */
    private fun pruneToCapacity() {
        val mappingKeys = settings.keys.filter { it.startsWith(KEY_PREFIX) }
        val excess = mappingKeys.size - capacity
        if (excess <= 0) return
        mappingKeys
            .map { key -> key to settings.getInt(key, Int.MAX_VALUE) }
            .sortedBy { it.second }
            .take(excess)
            .forEach { settings.remove(it.first) }
    }

    private fun keyFor(payloadId: String): String = "$KEY_PREFIX$payloadId"

    companion object {
        /** 連番カウンタの保存キー。 */
        const val KEY_SEQUENCE = "notifIdSequence"

        /** payload.id ごとの通知 ID を保存するキーの接頭辞。 */
        const val KEY_PREFIX = "notifId/"

        /** 最初に払い出す通知 ID。0 は既定通知と衝突しやすいため 1 から始める。 */
        const val FIRST_ID = 1

        /** 対応表に保持する payload.id の上限。受信重複排除の上限と揃える。 */
        const val DEFAULT_CAPACITY = 1000
    }
}
