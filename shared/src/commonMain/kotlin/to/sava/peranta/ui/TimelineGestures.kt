package to.sava.peranta.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

/**
 * タイムラインアイテムのコンテキストメニューを開くジェスチャを付与する（§10.1）。
 * メニューはどちらのプラットフォームでもバブル右上のメニューボタンから開ける。ここで付けるのは
 * それに加えて使えるジェスチャで、持つかどうかがプラットフォームで異なるため expect/actual で分離する。
 * [onOpenMenu] には押された位置をこの Modifier を付けた要素のローカル座標で渡し、呼び出し側が
 * そこへメニューを出せるようにする。[enabled] が false のときは何も付けない。
 */
expect fun Modifier.timelineContextGesture(enabled: Boolean, onOpenMenu: (position: Offset) -> Unit): Modifier
