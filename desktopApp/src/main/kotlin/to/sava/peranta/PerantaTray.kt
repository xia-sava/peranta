package to.sava.peranta

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import java.awt.Color
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JDialog
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * タスクトレイ常駐アイコン。シングルクリックでウィンドウを復帰し、右クリックで Swing の
 * ポップアップメニューを開く。Compose 標準の Tray は AWT PopupMenu 固定で見た目が古く、
 * クリック操作も割り当てられないため、AWT SystemTray を直接扱う。
 */
@Composable
fun PerantaTray(
    onActivate: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHealthCheck: () -> Unit,
    onExit: () -> Unit,
) {
    val activate by rememberUpdatedState(onActivate)
    val openSettings by rememberUpdatedState(onOpenSettings)
    val openHealthCheck by rememberUpdatedState(onOpenHealthCheck)
    val exit by rememberUpdatedState(onExit)

    DisposableEffect(Unit) {
        if (!SystemTray.isSupported()) {
            return@DisposableEffect onDispose {}
        }

        val popup = JPopupMenu().apply {
            add(
                JMenuItem("設定(S)").apply {
                    mnemonic = KeyEvent.VK_S
                    addActionListener { openSettings() }
                },
            )
            add(
                JMenuItem("健康診断(H)").apply {
                    mnemonic = KeyEvent.VK_H
                    addActionListener { openHealthCheck() }
                },
            )
            addSeparator()
            add(
                JMenuItem("終了(X)").apply {
                    mnemonic = KeyEvent.VK_X
                    addActionListener { exit() }
                },
            )
        }
        // JPopupMenu はフォーカス可能な親が無いと外側クリックで閉じられないため、
        // 不可視の 1px ダイアログを親にする定石を使う。
        val anchor = JDialog().apply {
            isUndecorated = true
            setSize(1, 1)
        }
        popup.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {}
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {
                anchor.isVisible = false
            }
            override fun popupMenuCanceled(e: PopupMenuEvent) {
                anchor.isVisible = false
            }
        })

        fun showMenuAt(e: MouseEvent) {
            anchor.setLocation(e.x, e.y)
            anchor.isVisible = true
            popup.show(anchor.contentPane, 0, 0)
        }

        val trayIcon = TrayIcon(trayIconImage(), "Peranta").apply {
            isImageAutoSize = true
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1) activate()
                }

                override fun mousePressed(e: MouseEvent) {
                    if (e.isPopupTrigger) showMenuAt(e)
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (e.isPopupTrigger) showMenuAt(e)
                }
            })
        }
        SystemTray.getSystemTray().add(trayIcon)

        onDispose {
            SystemTray.getSystemTray().remove(trayIcon)
            anchor.dispose()
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
