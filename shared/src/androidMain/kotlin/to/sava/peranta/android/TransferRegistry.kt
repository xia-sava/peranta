package to.sava.peranta.android

import kotlinx.coroutines.Job

/**
 * 進行中の添付アップロードを転送 ID で管理するスレッドセーフな台帳（§4.3）。
 * [AttachmentTransferService] の onStartCommand（メインスレッド）とアップロードジョブの完了処理
 * （IO ディスパッチャ）から並行アクセスされるため、全操作を同期する。
 * あるアップロードの完了が他を止めないよう、[remove] は「取り除いた結果として空になったか」を返し、
 * 空になったときだけサービスを停止できるようにする。
 */
internal class TransferRegistry {

    private class Entry(val notificationId: Int, val job: Job)

    private val entries = linkedMapOf<String, Entry>()

    /** [transferId] のアップロードを登録する。 */
    @Synchronized
    fun register(transferId: String, notificationId: Int, job: Job) {
        entries[transferId] = Entry(notificationId, job)
    }

    /** [transferId] を取り除き、取り除いた結果として台帳が空になったら true を返す。 */
    @Synchronized
    fun remove(transferId: String): Boolean {
        entries.remove(transferId)
        return entries.isEmpty()
    }

    /** [transferId] の進捗通知 ID。未登録なら null。 */
    @Synchronized
    fun notificationIdOf(transferId: String): Int? = entries[transferId]?.notificationId

    /** [transferId] のアップロードジョブ。未登録なら null。 */
    @Synchronized
    fun jobOf(transferId: String): Job? = entries[transferId]?.job

    /** [transferId] が進行中か。 */
    @Synchronized
    fun contains(transferId: String): Boolean = transferId in entries

    /** 進行中のアップロードが無いか。 */
    @Synchronized
    fun isEmpty(): Boolean = entries.isEmpty()
}
