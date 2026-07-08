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
        }
        jvmMain.dependencies {
            implementation(libs.cryptography.provider.jdk)
            implementation(libs.ktor.client.cio)
            implementation(libs.multiplatform.settings.noArg)
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
                )
            }
            excludes {
                // net パッケージ内の HTTP クライアント生成配線（実 ntfy 接続が必要）。
                classes("to.sava.peranta.net.JvmNtfyHttpKt")
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
