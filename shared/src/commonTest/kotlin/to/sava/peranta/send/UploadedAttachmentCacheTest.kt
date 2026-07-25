package to.sava.peranta.send

import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UploadedAttachmentCacheTest {

    private fun ref(blobId: String, expiresAt: Long? = null) = AttachmentRef(
        blobId = blobId,
        url = "https://host/file/$blobId",
        fileName = "notification-1.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1024,
        kind = AttachmentKind.IMAGE,
        blobExpiresAtEpochMillis = expiresAt,
        enc = BlobEnc(keyId = "k1", saltBase64 = "c2FsdA==", chunkSize = 1024, totalChunks = 1),
    )

    /** 記録した内容ハッシュで同じ参照を引ける（同一画像の再アップロードを省ける）。 */
    @Test
    fun remembersRefByContentHash() {
        val cache = UploadedAttachmentCache()
        cache.remember("hash-a", ref("blob-a"))

        assertEquals("blob-a", cache.find("hash-a", now = 1000)?.blobId)
    }

    /** 未記録の内容ハッシュでは null（アップロードが必要）。 */
    @Test
    fun unknownContentHashIsNotFound() {
        assertNull(UploadedAttachmentCache().find("hash-a", now = 1000))
    }

    /** 保持期限を過ぎた参照は配っても取得できないため、引くときに落とす。 */
    @Test
    fun expiredRefIsDropped() {
        val cache = UploadedAttachmentCache()
        cache.remember("hash-a", ref("blob-a", expiresAt = 500))

        assertNull(cache.find("hash-a", now = 1000))
        assertNull(cache.find("hash-a", now = 100))
    }

    /** 期限が未設定の参照は失効しない。 */
    @Test
    fun refWithoutExpiryNeverExpires() {
        val cache = UploadedAttachmentCache()
        cache.remember("hash-a", ref("blob-a"))

        assertEquals("blob-a", cache.find("hash-a", now = Long.MAX_VALUE)?.blobId)
    }

    /** 上限を超えたら最古から淘汰し、直近の記録は残る。 */
    @Test
    fun oldestEntryIsEvictedBeyondCapacity() {
        val cache = UploadedAttachmentCache(capacity = 2)
        cache.remember("hash-a", ref("blob-a"))
        cache.remember("hash-b", ref("blob-b"))
        cache.remember("hash-c", ref("blob-c"))

        assertNull(cache.find("hash-a", now = 1000))
        assertEquals("blob-b", cache.find("hash-b", now = 1000)?.blobId)
        assertEquals("blob-c", cache.find("hash-c", now = 1000)?.blobId)
    }

    /** 同じ内容ハッシュを上書き記録すると最新扱いになり、淘汰の順序が後ろへ回る。 */
    @Test
    fun rememberingAgainRefreshesRecency() {
        val cache = UploadedAttachmentCache(capacity = 2)
        cache.remember("hash-a", ref("blob-a"))
        cache.remember("hash-b", ref("blob-b"))
        cache.remember("hash-a", ref("blob-a2"))
        cache.remember("hash-c", ref("blob-c"))

        assertNull(cache.find("hash-b", now = 1000))
        assertEquals("blob-a2", cache.find("hash-a", now = 1000)?.blobId)
    }
}
