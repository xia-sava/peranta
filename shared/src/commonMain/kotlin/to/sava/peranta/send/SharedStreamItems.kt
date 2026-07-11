package to.sava.peranta.send

/**
 * 共有シート（`ACTION_SEND` / `ACTION_SEND_MULTIPLE`）で渡された添付を単数・複数の両経路から取り出す（§4.3）。
 * 単数（[single]）が有れば 1 件のリストにし、無ければ複数（[multiple]）を使い、どちらも無ければ空にする。
 * Android の Uri 抽出から純粋なリスト合成部分だけを切り出したもので、[T] は呼び出し側の型（Uri 等）。
 */
fun <T> sharedStreamItems(single: T?, multiple: List<T>?): List<T> {
    if (single != null) return listOf(single)
    return multiple.orEmpty()
}
