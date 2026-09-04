package to.sava.peranta.ui

import androidx.compose.ui.Modifier

/**
 * タイムラインアイテムのコンテキストメニューを開くジェスチャを付与する（§10.1）。
 * メニューはどちらのプラットフォームでもバブル右上のメニューボタンから開ける。ここで付けるのは
 * それに加えて使えるジェスチャで、持つかどうかがプラットフォームで異なるため expect/actual で分離する。
 * [enabled] が false のときは何も付けない。
 */
expect fun Modifier.timelineContextGesture(enabled: Boolean, onOpenMenu: () -> Unit): Modifier
