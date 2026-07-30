import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(projects.shared)

    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.foundation)
    implementation(compose.material3)
    implementation(libs.zxing.android.embedded)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

// 版数の単一ソース（gradle プロパティ）。配布時は -Pperanta.versionCode / -Pperanta.versionName で上書きする。
val perantaVersionCode = providers.gradleProperty("peranta.versionCode").getOrElse("1")
val perantaVersionName = providers.gradleProperty("peranta.versionName").getOrElse("0.0.0")

// 配布用の署名鍵。設定が無ければ署名なしで組む（手元の release ビルドを鍵無しで通すため）。
val releaseKeystore = providers.environmentVariable("PERANTA_RELEASE_KEYSTORE").orNull

android {
    namespace = "to.sava.peranta"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "to.sava.peranta"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = perantaVersionCode.toInt()
        versionName = perantaVersionName
    }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("PERANTA_RELEASE_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("PERANTA_RELEASE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("PERANTA_RELEASE_KEY_PASSWORD").orNull
            }
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            // Compose Multiplatform と kotlinx.serialization がリフレクションに依存するため、縮小と難読化は行わない。
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
