package to.sava.peranta.toast

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.CoroutineScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.launch
import to.sava.peranta.ui.PerantaTheme
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** トーストの横幅。 */
private const val TOAST_WIDTH = 380

/** 画面の端からトーストまでの余白（AWT のユーザ空間座標）。 */
private const val TOAST_SCREEN_MARGIN = 12

/** 影を描くためにウィンドウ内側へ空けておく透明な余白。 */
private const val TOAST_SHADOW_MARGIN = 12

/** 現れる・消えるアニメーションの長さ。 */
private const val APPEAR_MILLIS = 180
private const val DISAPPEAR_MILLIS = 140

/** 現れるときに下から持ち上げる距離。 */
private const val APPEAR_RISE = 20

/** ドラッグと見なし始める移動量（AWT のユーザ空間座標）。 */
private const val SWIPE_SLOP = 5

/** 消すときに画面外へ送り出す横移動量（トースト幅に対する割合）。 */
private const val SWIPE_EXIT_FRACTION = 1.1f

/** 送り出す・元の位置へ戻すアニメーションの長さ。 */
private const val SWIPE_EXIT_MILLIS = 160
private const val SWIPE_RETURN_MILLIS = 200

/**
 * 表示中のトーストをまとめて描く。Desktop の application スコープから 1 度だけ呼ぶ。
 * 1 トーストが 1 ウィンドウで、画面右下に古いものから順に積み上がる。
 */
@Composable
fun ToastHost(toaster: ComposeToaster) {
    val stack = remember { ToastStack() }
    val order = toaster.active.toList()
    order.forEach { toast ->
        key(toast) {
            ToastWindow(toast = toast, stack = stack, order = order)
        }
    }
}

/**
 * トースト 1 件を独立したウィンドウとして画面右下に出す。
 * 枠なし・透過・常に最前面・フォーカスを奪わない設定で、内容の高さに合わせてウィンドウが伸びる。
 * 横方向のドラッグにはウィンドウごと追従し、振り切ったところで消す。
 */
@Composable
private fun ToastWindow(toast: ActiveToast, stack: ToastStack, order: List<ActiveToast>) {
    val state = rememberWindowState(
        size = DpSize(TOAST_WIDTH.dp, Dp.Unspecified),
        position = WindowPosition(Alignment.BottomEnd),
    )
    Window(
        onCloseRequest = { toast.finish(ToastResult.Dismissed) },
        state = state,
        title = "Peranta",
        undecorated = true,
        transparent = true,
        resizable = false,
        focusable = false,
        alwaysOnTop = true,
    ) {
        var windowHeight by remember { mutableStateOf(window.height) }
        DisposableEffect(window) {
            val listener = object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent) {
                    windowHeight = window.height
                }
            }
            window.addComponentListener(listener)
            windowHeight = window.height
            onDispose { window.removeComponentListener(listener) }
        }

        // ウィンドウの高さに縛られずに測った内容の高さ（Compose の px）。
        var contentHeight by remember { mutableStateOf(0) }
        // 画像や送信者アイコンが後から届くと内容が伸びるため、測り直すたびに高さを合わせる。
        // ウィンドウが伸びないままだと、内容を縦に並べる際の残り高さが尽き、下端のボタンが潰される。
        LaunchedEffect(contentHeight) {
            if (contentHeight <= 0) return@LaunchedEffect
            // Compose の px と AWT のユーザ空間座標は HiDPI で倍率が異なる。WindowState の寸法は
            // 後者に対応するため、画面の変換倍率で割ってから渡す。
            val scale = window.graphicsConfiguration.defaultTransform.scaleY
            state.size = DpSize(TOAST_WIDTH.dp, ceil(contentHeight / scale).toFloat().dp)
        }

        LaunchedEffect(windowHeight) { stack.report(toast, windowHeight) }
        DisposableEffect(Unit) { onDispose { stack.forget(toast) } }

        val appear = remember { Animatable(0f) }
        val slide = remember { Animatable(0f) }
        var closingWith by remember { mutableStateOf<ToastResult?>(null) }

        val workArea = remember { screenWorkArea() }
        val offsetBelow = stack.offsetBelow(order, toast)
        val windowWidth = window.width.takeIf { it > 0 } ?: TOAST_WIDTH
        val slideX = (slide.value * windowWidth).roundToInt()
        SideEffect {
            // Dp を介すと HiDPI で密度が二重に掛かるため、AWT のユーザ空間座標へ直接置く。
            window.setLocation(
                workArea.x + workArea.width - windowWidth - TOAST_SCREEN_MARGIN + slideX,
                workArea.y + workArea.height - windowHeight - offsetBelow - TOAST_SCREEN_MARGIN,
            )
        }

        LaunchedEffect(Unit) { appear.animateTo(1f, tween(APPEAR_MILLIS)) }
        LaunchedEffect(closingWith) {
            val result = closingWith ?: return@LaunchedEffect
            appear.animateTo(0f, tween(DISAPPEAR_MILLIS))
            toast.finish(result)
        }

        val interactionSource = remember { MutableInteractionSource() }
        val dragScope = rememberCoroutineScope()
        PerantaTheme {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // ウィンドウの高さを内容から決めるため、その高さを制約に測らないようにする。
                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                    .onSizeChanged { contentHeight = it.height }
                    .padding(TOAST_SHADOW_MARGIN.dp)
                    .hoverable(interactionSource)
                    .graphicsLayer {
                        alpha = appear.value * (1f - abs(slide.value)).coerceIn(0f, 1f)
                        translationY = (1f - appear.value) * APPEAR_RISE.dp.toPx()
                    }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                trackSwipe(
                                    slide = slide,
                                    dragScope = dragScope,
                                    windowWidth = { window.width.takeIf { it > 0 } ?: TOAST_WIDTH },
                                    onDismiss = { toast.finish(ToastResult.Dismissed) },
                                )
                            }
                        }
                    },
            ) {
                ToastCard(
                    item = toast.item,
                    darkTheme = isSystemInDarkTheme(),
                    onResult = { result -> if (closingWith == null) closingWith = result },
                )
            }
        }
    }
}

/**
 * 押下から離すまでの 1 回分のドラッグを [slide] へ反映し、振り切っていれば [onDismiss] を呼ぶ。
 *
 * ウィンドウごと動かすため、移動量はウィンドウ内の相対座標ではなく画面上のマウス位置から測る
 * （相対座標だとウィンドウの移動がそのまま次の入力に混ざり、左右に振動する）。
 */
private suspend fun AwaitPointerEventScope.trackSwipe(
    slide: Animatable<Float, *>,
    dragScope: CoroutineScope,
    windowWidth: () -> Int,
    onDismiss: () -> Unit,
) {
    val down = awaitFirstDown(requireUnconsumed = false)
    val originX = pointerScreenX() ?: return
    var dragging = false
    val samples = ArrayDeque<Pair<Long, Int>>()
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        if (!change.pressed) break
        val moved = (pointerScreenX() ?: originX) - originX
        if (!dragging && abs(moved) < SWIPE_SLOP) continue
        dragging = true
        change.consume()
        samples.addLast(change.uptimeMillis to moved)
        while (samples.size > 2 &&
            samples.last().first - samples.first().first > SWIPE_VELOCITY_WINDOW_MILLIS
        ) {
            samples.removeFirst()
        }
        dragScope.launch { slide.snapTo(moved.toFloat() / windowWidth()) }
    }
    if (!dragging) return
    val velocity = swipeVelocity(samples)
    dragScope.launch {
        val direction = swipeDismissDirection(slide.value, velocity)
        if (direction == 0f) {
            slide.animateTo(0f, tween(SWIPE_RETURN_MILLIS))
        } else {
            slide.animateTo(direction * SWIPE_EXIT_FRACTION, tween(SWIPE_EXIT_MILLIS))
            onDismiss()
        }
    }
}

/** タスクバーを除いた画面の作業領域（AWT のユーザ空間座標）。 */
private fun screenWorkArea(): Rectangle =
    GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds

/** 画面上のマウス位置。取得できなければ null。 */
private fun pointerScreenX(): Int? = MouseInfo.getPointerInfo()?.location?.x
