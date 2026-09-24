import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
    kotlin("android")
}

val repoRoot = rootDir.parentFile.parentFile

// ABI -> Rust target triple for the bundled nocturned binary. The daemon is
// packaged as libnocturned.so under jniLibs so Android installs it to
// nativeLibraryDir with execute permission.
val cargoAbis = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "x86_64" to "x86_64-linux-android",
)

val sdkDir: File = listOfNotNull(
    (findProperty("sdk.dir") as String?)?.let { File(it) },
    System.getenv("ANDROID_HOME")?.let { File(it) },
    System.getenv("ANDROID_SDK_ROOT")?.let { File(it) },
).firstOrNull { it.isDirectory }
    ?: error("Android SDK not found; set sdk.dir in local.properties or ANDROID_HOME")

val ndkDir = File(sdkDir, "ndk").listFiles()
    ?.filter { it.isDirectory }
    ?.maxByOrNull { it.name }
    ?: error("No NDK installed under ${File(sdkDir, "ndk")}")

val llvmBin = File(ndkDir, "toolchains/llvm/prebuilt").listFiles()
    ?.filter { it.isDirectory }
    ?.firstOrNull()
    ?.let { File(it, "bin") }
    ?: error("No LLVM toolchain found under $ndkDir")

val minApiForToolchain = 21

cargoAbis.forEach { (abi, triple) ->
    val envTriple = triple.replace("-", "_")
    val clang = File(llvmBin, "${triple}${minApiForToolchain}-clang")
    val clangxx = File(llvmBin, "${triple}${minApiForToolchain}-clang++")

    val buildDaemon = tasks.register<Exec>("cargoBuild_${envTriple}") {
        workingDir = repoRoot
        commandLine("cargo", "build", "--release", "-p", "nocturned", "--target", triple)
        environment("ANDROID_NDK", ndkDir.absolutePath)
        environment("CC_$envTriple", clang.absolutePath)
        environment("CXX_$envTriple", clangxx.absolutePath)
        environment("AR_$envTriple", File(llvmBin, "llvm-ar").absolutePath)
        environment(
            "CARGO_TARGET_${envTriple.uppercase()}_LINKER",
            clang.absolutePath,
        )
        // audiopus_sys builds vendored opus via CMake; its CMakeLists
        // declares < 3.5, removed in CMake 4.x (ignored by older CMake).
        environment("CMAKE_POLICY_VERSION_MINIMUM", "3.5")
        inputs.dir(File(repoRoot, "crates/daemon/src"))
        outputs.file(File(repoRoot, "target/$triple/release/nocturned"))
    }

    tasks.register<Copy>("bundleDaemon_$envTriple") {
        dependsOn(buildDaemon)
        from(File(repoRoot, "target/$triple/release")) {
            include("nocturned")
            rename("nocturned", "libnocturned.so")
        }
        into(layout.buildDirectory.dir("jniLibs/$abi"))
    }
}

val bundleUiAssets = tasks.register<Copy>("bundleUiAssets") {
    from(File(repoRoot, "packages/ui/dist")) {
        into("ui")
    }
    into(layout.buildDirectory.dir("generatedAssets/webapps"))
    inputs.dir(File(repoRoot, "packages/ui/dist"))
}

android {
    namespace = "org.nocturne.emulator"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.nocturne.emulator"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters += cargoAbis.keys
        }
    }

    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("jniLibs"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generatedAssets"))

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.named("preBuild") {
    dependsOn(bundleUiAssets)
    cargoAbis.keys.forEach { abi ->
        dependsOn("bundleDaemon_${cargoAbis[abi]!!.replace("-", "_")}")
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(kotlin("stdlib"))
}
