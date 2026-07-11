package to.sava.peranta.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Peranta Blob Format の現行バージョン（§4.3）。 */
const val BLOB_FORMAT_VERSION: Int = 1

/** 添付の種別。JSON では小文字文字列で表現する。 */
@Serializable
enum class AttachmentKind {
    @SerialName("image")
    IMAGE,

    @SerialName("file")
    FILE,
}

/**
 * 暗号化 blob を復号するのに必要なパラメータ（§4.3、Peranta Blob Format v1）。
 * [saltBase64] は HKDF で blobKey を導出するときの 16 バイト salt の base64 表現。
 * [chunkSize] は暗号化時のチャンクサイズで、復号側は許容範囲を検証する。
 * [totalChunks] は総チャンク数で、平文サイズと chunkSize から決まる整合値を検証する。
 */
@Serializable
data class BlobEnc(
    val v: Int = BLOB_FORMAT_VERSION,
    val keyId: String,
    val saltBase64: String,
    val chunkSize: Int,
    val totalChunks: Long,
)

/**
 * 転送ペイロードに埋め込む添付の参照（§4.3）。blob 本体は暗号化して blobTopic へ置き、
 * その所在（[url]）と復号パラメータ（[enc]）・表示メタをここに載せて配送する。
 * [blobId] は AAD へ束縛する blob の UUID。[url] は ntfy の `/file/` ダウンロード URL。
 * [sizeBytes] は平文の総バイト数で、復号側の検証（totalChunks 整合・最終チャンク長・上限）に使う。
 * [blobExpiresAtEpochMillis] はサーバ側の添付保持期限で、期限後はダウンロード不可になる。
 */
@Serializable
data class AttachmentRef(
    val blobId: String,
    val url: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val kind: AttachmentKind,
    val blobExpiresAtEpochMillis: Long? = null,
    val enc: BlobEnc,
)
