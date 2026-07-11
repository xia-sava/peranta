import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.kover)
}

kotlin {
    jvm()

    androidLibrary {
        namespace = "to.sava.peranta.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.cryptography.provider.jdk)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.multiplatform.settings.noArg)
            implementation(libs.androidx.work.runtime)
            implementation(libs.unifiedpush.connector)
        }
        jvmMain.dependencies {
            implementation(libs.cryptography.provider.jdk)
            implementation(libs.ktor.client.cio)
            implementation(libs.multiplatform.settings.noArg)
            implementation(libs.zxing.core)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.cryptography.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.multiplatform.settings)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.multiplatform.settings.test)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.websockets)
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}

kover {
    reports {
        filters {
            // 計測対象はロジック層の 6 パッケージに限定する。
            // これにより UI（Composable）・platform 配線・DesktopReceiver（実 ntfy 接続が必要な
            // 組み立て配線、root パッケージ）は自動的に対象外となる。
            includes {
                packages(
                    "to.sava.peranta.model",
                    "to.sava.peranta.crypto",
                    "to.sava.peranta.net",
                    "to.sava.peranta.config",
                    "to.sava.peranta.timeline",
                    "to.sava.peranta.receive",
                    "to.sava.peranta.filter",
                    "to.sava.peranta.send",
                    "to.sava.peranta.blob",
                    "to.sava.peranta.roster",
                    "to.sava.peranta.pairing",
                    "to.sava.peranta.toast",
                    "to.sava.peranta.update",
                )
            }
            excludes {
                // net パッケージ内の HTTP クライアント生成配線（実 ntfy 接続が必要）。
                classes("to.sava.peranta.net.JvmNtfyHttpKt")
                // blob パッケージのうち ntfy 添付の HTTP アップロード/ダウンロード配線（実 ntfy 接続が必要。
                // 純粋ロジックは BlobFormat / BlobCipher で、契約は FakeBlobTransport のテストで担保する）。
                classes("to.sava.peranta.blob.KtorBlobTransport")
                classes("to.sava.peranta.blob.KtorBlobTransportKt")
                classes("to.sava.peranta.blob.NtfyPublishResponse")
                classes("to.sava.peranta.blob.NtfyAttachment")
                // pairing パッケージのうち QR 生成・PNG 描画の配線（zxing と画像 I/O が要る。
                // 生成結果は jvmTest の round-trip（PNG を再デコード）で振る舞いを担保する）。
                classes("to.sava.peranta.pairing.QrCodeKt")
                classes("to.sava.peranta.pairing.QrMatrix")
                // toast パッケージのうち SnoreToast プロセス起動・exe 展開・OS 判定・データ定義の配線
                // （実行に Windows と同梱 exe が要る。純粋ロジックは SnoreToastCommand / ToastContentKt）。
                classes("to.sava.peranta.toast.SnoreToastToaster")
                classes("to.sava.peranta.toast.SnoreToastResolverKt")
                classes("to.sava.peranta.toast.NoOpToaster")
                classes("to.sava.peranta.toast.ReceivedNotificationToast")
                classes("to.sava.peranta.toast.ToastResult")
                // 自己更新のプラットフォーム別インストーラ・配線（OS のインストーラ/ブラウザ起動・実 HTTP が必要）。
                classes("to.sava.peranta.update.AndroidUpdateInstaller")
                classes("to.sava.peranta.update.DesktopUpdateInstaller")
                classes("to.sava.peranta.update.AndroidUpdater")
                classes("to.sava.peranta.update.DesktopUpdater")
                // @Preview 関数と Compose 生成コード。
                annotatedBy("androidx.compose.ui.tooling.preview.Preview")
                annotatedBy("androidx.compose.runtime.Composable")
            }
        }

        verify {
            rule("ロジック層パッケージ毎の行カバレッジ") {
                groupBy = GroupingEntityType.PACKAGE
                bound {
                    minValue = 80
                    coverageUnits = CoverageUnit.LINE
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn("koverVerify")
}
