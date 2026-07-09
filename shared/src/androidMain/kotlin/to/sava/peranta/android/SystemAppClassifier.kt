package to.sava.peranta.android

import android.content.pm.PackageManager
import to.sava.peranta.filter.isImplicitlySystemPackage

/**
 * 送信側の暗黙システム除外判定（§7）を、[PackageManager] のランチャー有無から組み立てる。
 * ランチャーアイコン（起動 Intent）を持つアプリは FLAG_SYSTEM でも通常アプリとして転送対象に含め、
 * プリインの Gmail 等を誤って除外しない。
 *
 * [isCrossProfilePackage] が真（work profile 等、自ユーザーと異なるプロファイルの通知）のときは、
 * 個人プロファイルの [packageManager] からランチャー有無を判定できないため、その判定に基づく暗黙除外を
 * 行わない。判定の合成ロジックは commonMain の純関数へ委ねる。
 */
fun packageManagerSystemPackagePredicate(
    packageManager: PackageManager,
    isCrossProfilePackage: Boolean = false,
): (String) -> Boolean =
    { packageName ->
        isImplicitlySystemPackage(
            packageName = packageName,
            hasLauncherIcon = packageManager.getLaunchIntentForPackage(packageName) != null,
            isCrossProfilePackage = isCrossProfilePackage,
        )
    }
