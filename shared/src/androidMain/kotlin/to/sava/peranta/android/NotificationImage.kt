package to.sava.peranta.android

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import to.sava.peranta.blob.MAX_NOTIFICATION_IMAGE_BYTES
import to.sava.peranta.blob.MAX_SENDER_ICON_BYTES
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.roundToInt

/** 転送する通知画像の MIME 型（§4.3.1）。 */
const val NOTIFICATION_IMAGE_MIME: String = "image/jpeg"

/** 転送する送信者アイコンの MIME 型（§4.3.1）。輪郭の透過を保つため PNG とする。 */
const val SENDER_ICON_MIME: String = "image/png"

/** 転送する通知画像の長辺の上限 px。これを超える画像は縮小してから符号化する（§4.3.1）。 */
private const val MAX_IMAGE_LONG_EDGE: Int = 1440

/** 転送する送信者アイコンの長辺の上限 px。ヘッダの小さな円に収まれば足りる（§4.3.1）。 */
private const val MAX_ICON_LONG_EDGE: Int = 128

/** 通知画像を JPEG へ符号化するときの品質。 */
private const val IMAGE_JPEG_QUALITY: Int = 85

/** PNG は可逆のため品質指定を使わないが、[Bitmap.compress] が引数を要求する。 */
private const val ICON_PNG_QUALITY: Int = 100

/**
 * 通知が持つ本文画像（BigPictureStyle）を取り出す（§4.3.1）。持たない通知では null。
 * Android 12 以降は `Icon` で載るため、Bitmap 直載せの旧形式と両方を見る。
 */
fun notificationImageOf(context: Context, notification: Notification): Bitmap? {
    val extras = notification.extras
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        extras.parcelable(Notification.EXTRA_PICTURE_ICON, Icon::class.java)
            ?.loadDrawable(context)
            ?.toBitmap()
            ?.let { return it }
    }
    return extras.parcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
}

/**
 * 通知が持つ送信者アイコン（largeIcon）を取り出す（§4.3.1）。持たない通知では null。
 * メッセージアプリでは相手の連絡先写真やアバターがここに載る。
 */
fun senderIconOf(context: Context, notification: Notification): Bitmap? =
    notification.getLargeIcon()?.loadDrawable(context)?.toBitmap()

/**
 * 通知画像を配送用のバイト列へ符号化する（§4.3.1）。長辺を上限まで縮小して JPEG にする。
 * 符号化しても上限バイト数に収まらない場合は null を返し、呼び出し側は添付を諦める。
 */
fun encodeNotificationImage(image: Bitmap): ByteArray? =
    encodeScaled(image, MAX_IMAGE_LONG_EDGE, Bitmap.CompressFormat.JPEG, IMAGE_JPEG_QUALITY, MAX_NOTIFICATION_IMAGE_BYTES)

/**
 * 送信者アイコンを配送用のバイト列へ符号化する（§4.3.1）。挙動は [encodeNotificationImage] と同じで、
 * 円に切り抜かれた輪郭の透過を残すため PNG にする。
 */
fun encodeSenderIcon(icon: Bitmap): ByteArray? =
    encodeScaled(icon, MAX_ICON_LONG_EDGE, Bitmap.CompressFormat.PNG, ICON_PNG_QUALITY, MAX_SENDER_ICON_BYTES)

/**
 * 長辺を [maxLongEdge] まで縮めて符号化する。[maxBytes] に収まらなければ null。
 * [maxBytes] は受信側の自動取得の判断と同じ定義（[MAX_NOTIFICATION_IMAGE_BYTES] /
 * [MAX_SENDER_ICON_BYTES]）を渡す。
 */
private fun encodeScaled(
    bitmap: Bitmap,
    maxLongEdge: Int,
    format: Bitmap.CompressFormat,
    quality: Int,
    maxBytes: Long,
): ByteArray? {
    val source = bitmap.toSoftwareBitmap()
    val scaled = source.scaleToLongEdge(maxLongEdge)
    val buffer = ByteArrayOutputStream()
    scaled.compress(format, quality, buffer)
    if (scaled !== source) scaled.recycle()
    if (source !== bitmap) source.recycle()
    return buffer.toByteArray().takeIf { it.size.toLong() <= maxBytes }
}

/** [bytes] の SHA-256 を 16 進表記で返す。同一画像の再アップロードを避ける照合キーに使う（§4.3.1）。 */
fun contentHashOf(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** 長辺が [maxLongEdge] を超えるときだけ縮小する。収まっていれば元の Bitmap をそのまま返す。 */
private fun Bitmap.scaleToLongEdge(maxLongEdge: Int): Bitmap {
    val longEdge = maxOf(width, height)
    if (longEdge <= maxLongEdge) return this
    val ratio = maxLongEdge.toFloat() / longEdge
    return Bitmap.createScaledBitmap(
        this,
        (width * ratio).roundToInt().coerceAtLeast(1),
        (height * ratio).roundToInt().coerceAtLeast(1),
        true,
    )
}

/**
 * 縮小・符号化できる形式へ揃える。ハードウェア Bitmap は画素を直接読めず
 * [Bitmap.createScaledBitmap] が失敗するため、ARGB_8888 へ複製する。
 */
private fun Bitmap.toSoftwareBitmap(): Bitmap =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && config == Bitmap.Config.HARDWARE) {
        copy(Bitmap.Config.ARGB_8888, false) ?: this
    } else {
        this
    }

/** Drawable を Bitmap にする。Bitmap 由来ならその実体を、それ以外は実寸のキャンバスへ描いて取り出す。 */
private fun Drawable.toBitmap(): Bitmap? {
    (this as? BitmapDrawable)?.bitmap?.let { return it }
    if (intrinsicWidth <= 0 || intrinsicHeight <= 0) return null
    val bitmap = Bitmap.createBitmap(intrinsicWidth, intrinsicHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap
}

/**
 * Parcelable の取り出し。型指定版が使えない API レベルでは非推奨の総称版に落ちる。
 * 総称版は型を実行時に確かめられないため、取り出せた値が [type] であることは呼び出し側が保つ。
 */
@Suppress("DEPRECATION", "UNCHECKED_CAST")
private fun <T> Bundle.parcelable(key: String, type: Class<T>): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelable(key, type)
    } else {
        getParcelable(key) as? T
    }
