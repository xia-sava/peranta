package to.sava.peranta

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import java.awt.Color
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage

/**
 * タスクトレイ常駐アイコン。左シングルクリックでウィンドウを復帰し、右クリックで OS ネイティブの
 * メニュー（開く／設定／終了）を出す。Compose 標準の Tray は左クリックでの復帰を割り当てられないため、
 * AWT SystemTray を直接扱う。メニューは [TrayIcon.popupMenu] に委ねて表示位置を OS へ任せる。
 */
@Composable
fun PerantaTray(
    onActivate: () -> Unit,
    onOpenSettings: () -> Unit,
    onExit: () -> Unit,
) {
    val activate by rememberUpdatedState(onActivate)
    val openSettings by rememberUpdatedState(onOpenSettings)
    val exit by rememberUpdatedState(onExit)

    DisposableEffect(Unit) {
        if (!SystemTray.isSupported()) {
            return@DisposableEffect onDispose {}
        }

        val popup = PopupMenu().apply {
            add(MenuItem("開く").apply { addActionListener { activate() } })
            add(MenuItem("設定").apply { addActionListener { openSettings() } })
            addSeparator()
            add(MenuItem("終了").apply { addActionListener { exit() } })
        }

        val trayIcon = TrayIcon(trayIconImage(), "Peranta").apply {
            isImageAutoSize = true
            popupMenu = popup
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1) activate()
                }
            })
        }
        SystemTray.getSystemTray().add(trayIcon)

        onDispose {
            SystemTray.getSystemTray().remove(trayIcon)
        }
    }
}

/** ウィンドウアイコンと同じ意匠（青の角丸に白丸）をトレイ用にラスタ描画する。 */
private fun trayIconImage(): BufferedImage {
    val size = 32
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Color(0x4C, 0x6E, 0xF5)
    g.fillRoundRect(0, 0, size, size, 10, 10)
    g.color = Color.WHITE
    val radius = size / 4
    g.fillOval(size / 2 - radius, size / 2 - radius, radius * 2, radius * 2)
    g.dispose()
    return image
}
