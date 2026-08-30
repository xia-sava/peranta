import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

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

// 版数は配布時に -Pperanta.versionCode / -Pperanta.versionName で渡す。渡されない手元のビルドは既定値を使う。
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

// provider の宣言と、Uri を組み立てる側の定数の食い違いを見る。
// 1 つのクラスが持てる authority は 1 つで、同じクラスで provider を 2 つ宣言すると後に書いた方の
// authority を引けない。ビルドも lint も通るため、端末へ Uri を渡した時点で初めて壊れる。
val verifyFileProviders = tasks.register("verifyFileProviders") {
    // doLast がビルドスクリプトのインスタンスを掴むと configuration cache へ入らないため、File だけを渡す。
    val manifest = layout.projectDirectory.file("src/main/AndroidManifest.xml").asFile
    val sources = layout.projectDirectory.dir("../shared/src/androidMain").asFile
    inputs.file(manifest)
    inputs.dir(sources)

    doLast {
        val providers = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(manifest)
            .getElementsByTagName("provider")
        val classNames = mutableListOf<String>()
        val authorities = mutableSetOf<String>()
        for (index in 0 until providers.length) {
            val provider = providers.item(index) as Element
            classNames += provider.getAttribute("android:name")
            authorities += provider.getAttribute("android:authorities").removePrefix("\${applicationId}")
        }

        classNames.groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .takeIf { it.isNotEmpty() }
            ?.let {
                throw GradleException(
                    "同じクラスで宣言した provider がある: ${it.joinToString()}。後に書いた方の authority は引けない",
                )
            }

        // Uri を組み立てる接尾辞は、宣言された authority と過不足なく揃っている必要がある。
        val suffixPattern = Regex("const val \\w*PROVIDER_SUFFIX(?:\\s*:\\s*\\w+)?\\s*=\\s*\"([^\"]+)\"")
        val declared = sources.walk()
            .filter { it.extension == "kt" }
            .flatMap { suffixPattern.findAll(it.readText()) }
            .map { it.groupValues[1] }
            .toSet()
        if (declared != authorities) {
            throw GradleException(
                "provider の authority と、Uri を組み立てる定数が食い違っている: " +
                    "マニフェスト=${authorities.sorted()} 定数=${declared.sorted()}",
            )
        }
    }
}

tasks.named("check") { dependsOn(verifyFileProviders) }
