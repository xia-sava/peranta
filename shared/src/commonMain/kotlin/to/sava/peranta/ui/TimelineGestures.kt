package to.sava.peranta.ui

import androidx.compose.ui.Modifier

/**
 * タイムラインアイテムのコンテキストメニューを開くジェスチャを付与する（§10.1）。
 * プラットフォームでジェスチャが異なる（Android は長押し、Desktop は右クリック）ため
 * expect/actual で分離する。[enabled] が false のときは何も付けない。
 */
expect fun Modifier.timelineContextGesture(enabled: Boolean, onOpenMenu: () -> Unit): Modifier
