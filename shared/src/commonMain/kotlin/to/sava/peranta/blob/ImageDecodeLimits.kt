package to.sava.peranta.blob

import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef

/**
 * サムネイル・通知画像のデコードを試みる添付の符号化サイズ上限（§4.3）。
 * これを超える添付は画像でもデコードせず、種別アイコンにフォールバックする。
 */
const val MAX_THUMBNAIL_DECODE_BYTES: Long = 25L * 1024 * 1024

/**
 * デコードで確保してよいビットマップのピクセル数上限（§4.3）。
 *
 * 符号化サイズと展開後のピクセル数は独立で、単色の巨大画像は数 MB の PNG のまま
 * 数十億画素へ展開できる（[MAX_THUMBNAIL_DECODE_BYTES] だけでは止まらない）。
 * この上限は **確保しようとしているビットマップの画素数** に当てる。
 * Android は縮小デコード後の寸法を、Desktop は等倍で展開する寸法を渡す。
 *
 * 実際に撮られた写真（数千万画素）が通り、圧縮率だけを利用した画像が通らない値にする。
 */
const val MAX_DECODED_IMAGE_PIXELS: Long = 64L * 1024 * 1024

/**
 * [width]×[height] のビットマップが [MAX_DECODED_IMAGE_PIXELS] を超えるか。
 * 寸法を読めなかった場合（0 以下）も超過として扱い、デコードへ進ませない。
 */
fun exceedsDecodedPixelLimit(width: Int, height: Int): Boolean =
    width <= 0 || height <= 0 || width.toLong() * height.toLong() > MAX_DECODED_IMAGE_PIXELS

/**
 * 復号済みの添付をサムネイルにデコードしてよいか（§4.3）。画像（[AttachmentRef.kind] が
 * [AttachmentKind.IMAGE]）で、復号済みファイルの大きさ [fileSizeBytes] が [MAX_THUMBNAIL_DECODE_BYTES] 以内のもの。
 */
fun shouldDecodeThumbnail(ref: AttachmentRef, fileSizeBytes: Long): Boolean =
    ref.kind == AttachmentKind.IMAGE && fileSizeBytes <= MAX_THUMBNAIL_DECODE_BYTES
