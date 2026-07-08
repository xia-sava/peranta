package to.sava.peranta.filter

import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.SmsPayload

/** 履歴でセンシティブ本文を伏せる際に入れる文字列（§11）。 */
const val SENSITIVE_HISTORY_PLACEHOLDER: String = "（本文は記録しません）"

/**
 * 永続化する payload の本文を §11 に従って調整する。送信側・受信側の双方が使う。
 * [keepSensitive] が true なら [payload] をそのまま返す。false のとき、SMS と OTP 通知の本文を伏せる。
 * OTP 判定はタイトルと本文の連結で行うため、コードがタイトル側にある場合はタイトルも伏せる。
 * 伏せる必要が無いときは [payload] と同一インスタンスを返す（呼び出し側の同一性判定に使える）。
 */
fun payloadForPersistence(payload: Payload, keepSensitive: Boolean): Payload {
    if (keepSensitive) return payload
    return when (payload) {
        is SmsPayload -> payload.copy(text = SENSITIVE_HISTORY_PLACEHOLDER)
        is NotificationPayload ->
            if (looksLikeOtp("${payload.title} ${payload.text}")) {
                payload.copy(
                    title = if (hasOtpDigits(payload.title)) SENSITIVE_HISTORY_PLACEHOLDER else payload.title,
                    text = SENSITIVE_HISTORY_PLACEHOLDER,
                )
            } else {
                payload
            }

        else -> payload
    }
}
