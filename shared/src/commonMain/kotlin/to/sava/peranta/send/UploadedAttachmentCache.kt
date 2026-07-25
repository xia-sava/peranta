package to.sava.peranta.send

import to.sava.peranta.model.AttachmentRef

/** アップロード済み添付を覚えておく件数の既定の上限。 */
const val UPLOADED_ATTACHMENT_CACHE_CAPACITY: Int = 64

/**
 * 内容が同じ添付を上げ直さないための記憶（§4.3.1）。
 * メッセージアプリは未読会話の通知を繰り返し再投稿するため、同じ画像が何度も流れてくる。
 * blob の保持期限を過ぎた参照は配っても取得できないので、引くときに落とす。
 * 単一スレッド（通知リスナーのコールバック）での利用を前提とする。
 */
class UploadedAttachmentCache(private val capacity: Int = UPLOADED_ATTACHMENT_CACHE_CAPACITY) {

    private val refs = LinkedHashMap<String, AttachmentRef>()

    /** [contentHash] に対応する未失効の参照。未記録・期限切れなら null。 */
    fun find(contentHash: String, now: Long): AttachmentRef? {
        val ref = refs[contentHash] ?: return null
        if (ref.blobExpiresAtEpochMillis?.let { it < now } == true) {
            refs.remove(contentHash)
            return null
        }
        return ref
    }

    /** [contentHash] の内容を [ref] としてアップロード済みと記録する。上限超過分は最古から淘汰する。 */
    fun remember(contentHash: String, ref: AttachmentRef) {
        refs.remove(contentHash)
        refs[contentHash] = ref
        if (refs.size > capacity) {
            refs.remove(refs.iterator().next().key)
        }
    }
}
