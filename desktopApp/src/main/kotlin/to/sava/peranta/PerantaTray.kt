package to.sava.peranta

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

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

        val tray = SystemTray.getSystemTray()
        val trayIcon = TrayIcon(PerantaIcon.image(tray.trayIconSize.width), "Peranta").apply {
            isImageAutoSize = true
            popupMenu = popup
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1) activate()
                }
            })
        }
        tray.add(trayIcon)

        onDispose {
            tray.remove(trayIcon)
        }
    }
}
