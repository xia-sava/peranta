package to.sava.peranta.timeline

/**
 * エラーを OS 通知・トーストで知らせるときの件名（§10.1）。
 * 送ったコマンドに応答が無い（§3.4）ことは受信の失敗ではないため、受信エラーとは分けて示す。
 */
fun errorTitleOf(kind: ErrorKind): String = when (kind) {
    ErrorKind.COMMAND_UNACKNOWLEDGED -> "Peranta 操作の応答なし"
    else -> "Peranta 受信エラー"
}
