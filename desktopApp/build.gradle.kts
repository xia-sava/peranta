import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.WriteProperties
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.kermit)

    implementation(libs.compose.uiToolingPreview)
}

// 配布物の名前。インストール先ディレクトリ・ランチャー exe・スタートメニューの表示名になる。
val perantaPackageName = "Peranta"

// 版数の単一ソース（gradle プロパティ）。配布時は -Pperanta.versionCode / -Pperanta.versionName で上書きする。
val perantaVersionCode = providers.gradleProperty("peranta.versionCode").getOrElse("1")
val perantaVersionName = providers.gradleProperty("peranta.versionName").getOrElse("0.0.0")
val versionResourceDir = layout.buildDirectory.dir("generated/version")

// versionCode / versionName を生成リソースへ書き出す。run と jpackage 成果物の双方の classpath に載る。
val generateVersionProperties = tasks.register<WriteProperties>("generateVersionProperties") {
    destinationFile = versionResourceDir.map { it.file("peranta-version.properties") }
    property("versionCode", perantaVersionCode)
    property("versionName", perantaVersionName)
}

sourceSets["main"].resources.srcDir(versionResourceDir)

tasks.named<ProcessResources>("processResources") {
    dependsOn(generateVersionProperties)
}

compose.desktop {
    application {
        mainClass = "to.sava.peranta.MainKt"

        // 生成リソースを読めない実行経路（IDE の直接 run 等）でも版数が効くよう -D でも渡す。
        jvmArgs += listOf(
            "-Dperanta.versionCode=$perantaVersionCode",
            "-Dperanta.versionName=$perantaVersionName",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = perantaPackageName
            // 新版の MSI が旧版を置き換える条件は upgradeUuid の一致と版数の増加の両方なので、
            // インストーラの版数を配布物の versionName に追随させる。
            packageVersion = perantaVersionName

            windows {
                iconFile.set(project.file("icons/peranta.ico"))
                // 設定・鍵・履歴をユーザー領域に持つ単一ユーザー向けアプリなので、
                // インストールと更新に管理者権限を要求しない。
                perUserInstall = true
                // 版を上げた MSI が旧版を置き換えるよう、アップグレードコードを固定する
                // （省くとビルドごとに別の UUID が振られ、新旧が並存してしまう）。
                upgradeUuid = "d8b68daa-f997-4213-bdc3-bfbfe2a9fdc5"
                // スタートメニューへ起動口を作る。グループ名を省くと "Unknown" フォルダに入る。
                menu = true
                menuGroup = perantaPackageName
            }
        }
    }
}

// 開発用の設定上書き（PERANTA_* / withDevOverrides）と TLS ダウングレードを有効にするフラグ（§16）。
// compose が afterEvaluate で登録する `run`（開発起動）にだけ載せ、
// runDistributable や配布物のランチャー設定（jvmArgs）には載せない。
tasks.withType<JavaExec>().configureEach {
    if (name == "run") {
        systemProperty("peranta.devMode", "true")
    }
}
