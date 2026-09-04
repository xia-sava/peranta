package to.sava.peranta.ui

import androidx.compose.runtime.Composable

/**
 * 選択した本文をタイムライン自身のコンテキストメニューから複写できるようにする（§10.1）。
 * 選択の仕組みが持つメニューをそのまま出すとタイムラインのメニューと重なるため、プラットフォームごとに
 * 扱いが分かれる。[content] には選択を行う範囲（`SelectionContainer`）を渡す。
 */
@Composable
expect fun TimelineSelectionScope(content: @Composable () -> Unit)

/**
 * 選択中の本文をクリップボードへ入れる操作（§10.1）。選択が無いとき、またはコンテキストメニューでの
 * 複写を持たないプラットフォームでは null を返し、呼び出し側はメニューに項目を出さない。
 */
@Composable
expect fun copySelectionOrNull(): (() -> Unit)?
