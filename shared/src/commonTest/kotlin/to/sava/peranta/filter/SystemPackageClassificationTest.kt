package to.sava.peranta.filter

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ランチャー有無に基づく暗黙システム除外判定（§7）を検証する。 */
class SystemPackageClassificationTest {

    /** ランチャーアイコンを持つアプリは FLAG_SYSTEM 相当でも通常アプリ扱い（暗黙除外しない）。 */
    @Test
    fun launcherAppIsNotSystem() {
        assertFalse(isImplicitlySystemPackage("com.google.android.gm", hasLauncherIcon = true))
    }

    /** ランチャーを持たないアプリは暗黙システム除外扱いとする。 */
    @Test
    fun launcherlessAppIsSystem() {
        assertTrue(isImplicitlySystemPackage("com.example.background", hasLauncherIcon = false))
    }

    /** 既定システムパッケージは、ランチャーを持っていてもベースラインとの OR で暗黙除外になる。 */
    @Test
    fun defaultSystemPackageStaysSystemEvenWithLauncher() {
        assertTrue(isImplicitlySystemPackage("com.android.systemui", hasLauncherIcon = true))
    }

    /** カスタムのベースライン集合を渡すと、それに載ったパッケージも暗黙除外になる。 */
    @Test
    fun customBaselineExcludesListedPackage() {
        assertTrue(
            isImplicitlySystemPackage(
                "com.vendor.hidden",
                hasLauncherIcon = true,
                systemPackages = setOf("com.vendor.hidden"),
            ),
        )
    }

    /**
     * 別プロファイル（work profile 等）の通知は、個人プロファイルの PackageManager からランチャーが
     * 見えず [hasLauncherIcon] が偽でも、暗黙除外にしない（疑わしきは転送）。
     */
    @Test
    fun crossProfilePackageWithoutLauncherIsNotImplicitlySystem() {
        assertFalse(
            isImplicitlySystemPackage(
                "com.example.work.gmail",
                hasLauncherIcon = false,
                isCrossProfilePackage = true,
            ),
        )
    }

    /** 別プロファイルの通知でも、既定システムパッケージへの名前一致による暗黙除外は引き続き有効。 */
    @Test
    fun crossProfileDefaultSystemPackageStaysSystem() {
        assertTrue(
            isImplicitlySystemPackage(
                "com.android.systemui",
                hasLauncherIcon = false,
                isCrossProfilePackage = true,
            ),
        )
    }
}
