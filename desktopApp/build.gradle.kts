import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.WriteProperties
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.process.ExecOperations
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.security.MessageDigest
import javax.inject.Inject

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

/**
 * vendor の SnoreToast ソース（upstream tar + Peranta パッチ）から、Peranta 改造版
 * snoretoast.exe（-persistent 対応）をビルドするタスク。
 *
 * sha256 検証 → tar 展開 → git apply でパッチ適用 → cmd 経由で vcvars64 + cmake -G Ninja + ninja。
 * ツールチェーンのパスは gradle プロパティか環境変数で上書きできる。
 * 生成物が既にあれば Gradle の UP-TO-DATE 判定でスキップし、ツールチェーンも無ければ明快なエラーを出す。
 */
abstract class BuildSnoreToastTask
@Inject constructor(private val execOps: ExecOperations) : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val tarball: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sha256File: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val patchFile: RegularFileProperty

    /** 展開されるソースのトップディレクトリ名（tar 内のルート）。 */
    @get:Input
    abstract val sourceDirName: Property<String>

    @get:Input
    abstract val vcvarsPath: Property<String>

    @get:Input
    abstract val cmakePath: Property<String>

    @get:Input
    abstract val ninjaPath: Property<String>

    @get:Internal
    abstract val workDir: DirectoryProperty

    @get:OutputFile
    abstract val outputExe: RegularFileProperty

    @TaskAction
    fun build() {
        verifySha256()

        val vcvars = File(vcvarsPath.get())
        val cmake = File(cmakePath.get())
        val ninja = File(ninjaPath.get())
        val out = outputExe.get().asFile

        val toolchainMissing = listOf(vcvars, cmake, ninja).filterNot { it.exists() }
        if (toolchainMissing.isNotEmpty()) {
            if (out.exists()) {
                logger.warn(
                    "buildSnoreToast: toolchain not found ({}), keeping existing {}",
                    toolchainMissing.joinToString { it.path },
                    out.path,
                )
                return
            }
            throw GradleException(
                "buildSnoreToast: cannot build snoretoast.exe — missing toolchain: " +
                    toolchainMissing.joinToString { it.path } +
                    ". Override with -Pperanta.msvc.vcvars / -Pperanta.cmake / -Pperanta.ninja " +
                    "(or PERANTA_MSVC_VCVARS / PERANTA_CMAKE / PERANTA_NINJA), or provide a prebuilt exe at " +
                    out.path,
            )
        }

        val root = workDir.get().asFile
        val src = File(root, sourceDirName.get())
        src.deleteRecursively()
        root.mkdirs()

        extract(tarball.get().asFile, root)
        applyPatch(src, patchFile.get().asFile)
        compile(src, vcvars, cmake, ninja)

        val built = File(src, "build/bin/snoretoast.exe")
        if (!built.exists()) {
            throw GradleException("buildSnoreToast: expected build output not found at ${built.path}")
        }
        out.parentFile.mkdirs()
        built.copyTo(out, overwrite = true)
        logger.lifecycle("buildSnoreToast: produced {}", out.path)
    }

    private fun verifySha256() {
        val expected = sha256File.get().asFile.readText().trim().substringBefore(' ').lowercase()
        val digest = MessageDigest.getInstance("SHA-256")
        tarball.get().asFile.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            throw GradleException("buildSnoreToast: sha256 mismatch (expected=$expected actual=$actual)")
        }
    }

    private fun extract(tar: File, into: File) {
        execOps.exec {
            commandLine("tar", "-xf", tar.absolutePath, "-C", into.absolutePath)
        }
    }

    private fun applyPatch(src: File, patch: File) {
        execOps.exec {
            workingDir = src
            commandLine("git", "apply", "-p1", patch.absolutePath)
        }
    }

    private fun compile(src: File, vcvars: File, cmake: File, ninja: File) {
        val bat = File(src, "peranta-build.bat")
        bat.writeText(
            buildString {
                appendLine("@echo off")
                appendLine("call \"${vcvars.absolutePath}\" >nul")
                appendLine("if errorlevel 1 exit /b 1")
                appendLine(
                    "\"${cmake.absolutePath}\" -G Ninja " +
                        "-DCMAKE_MAKE_PROGRAM=\"${ninja.absolutePath}\" " +
                        "-DCMAKE_BUILD_TYPE=Release -B build -S . || exit /b 1",
                )
                appendLine("\"${ninja.absolutePath}\" -C build || exit /b 1")
            },
        )
        execOps.exec {
            workingDir = src
            commandLine("cmd", "/c", bat.absolutePath)
        }
    }
}

val snoreToastVendorDir = layout.projectDirectory.dir("vendor/snoretoast")
val snoreToastOutputDir = layout.buildDirectory.dir("generated/snoretoast")

/** gradle プロパティ → 環境変数 → 既定 の順で解決するツールチェーンパス。 */
fun toolchainPath(propertyName: String, envName: String, default: String) =
    providers.gradleProperty(propertyName)
        .orElse(providers.environmentVariable(envName))
        .orElse(default)

val buildSnoreToast = tasks.register<BuildSnoreToastTask>("buildSnoreToast") {
    group = "build"
    description = "vendor の SnoreToast ソースから Peranta 改造版 snoretoast.exe をビルドする"

    tarball.set(snoreToastVendorDir.file("snoretoast-v0.9.1.tar.bz2"))
    sha256File.set(snoreToastVendorDir.file("snoretoast-v0.9.1.tar.bz2.sha256"))
    patchFile.set(snoreToastVendorDir.file("peranta-snoretoast.patch"))
    sourceDirName.set("snoretoast-v0.9.1")

    vcvarsPath.set(
        toolchainPath(
            "peranta.msvc.vcvars",
            "PERANTA_MSVC_VCVARS",
            "C:\\Program Files (x86)\\Microsoft Visual Studio\\2022\\BuildTools\\VC\\Auxiliary\\Build\\vcvars64.bat",
        ),
    )
    cmakePath.set(
        toolchainPath(
            "peranta.cmake",
            "PERANTA_CMAKE",
            "C:\\Users\\xia\\AppData\\Local\\Android\\Sdk\\cmake\\4.1.2\\bin\\cmake.exe",
        ),
    )
    ninjaPath.set(
        toolchainPath(
            "peranta.ninja",
            "PERANTA_NINJA",
            "C:\\Users\\xia\\AppData\\Local\\Android\\Sdk\\cmake\\4.1.2\\bin\\ninja.exe",
        ),
    )

    workDir.set(layout.buildDirectory.dir("snoretoast-build"))
    outputExe.set(snoreToastOutputDir.map { it.file("snoretoast.exe") })
}

// 生成した snoretoast.exe をリソースに載せ、run / jpackage 成果物へ同梱する。
sourceSets["main"].resources.srcDir(snoreToastOutputDir)

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
    dependsOn(buildSnoreToast, generateVersionProperties)
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
            packageName = "to.sava.peranta"
            packageVersion = "1.0.0"

            windows {
                iconFile.set(project.file("icons/peranta.ico"))
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
