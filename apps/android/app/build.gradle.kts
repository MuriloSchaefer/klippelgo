plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// What this build is pinned to (docs/releases-and-data.md): one network's contract addresses, compiled in, and the
// release version in /VERSION. `-Pklippelgo.network=amoy` builds against deployments/amoy.json; the default is the
// local testnet, whose file `make deploy` writes.
val repoRoot = rootProject.file("../..")
val network = (findProperty("klippelgo.network") as String?)?.trim().orEmpty().ifEmpty { "local" }
require(Regex("[a-z0-9-]+").matches(network)) { "klippelgo.network '$network' is not a network name" }
val deploymentFile = repoRoot.resolve("deployments/$network.json")
val releaseVersion = repoRoot.resolve("VERSION").readText().trim()
val versionParts = Regex("""(\d{1,4})\.(\d{1,3})\.(\d{1,3})""").matchEntire(releaseVersion)?.groupValues?.drop(1)?.map { it.toInt() }
    ?: error("VERSION holds '$releaseVersion', not MAJOR.MINOR.PATCH with parts Android's version code can hold")
// A later release always has a larger code, which is what Android checks before it lets one replace another.
val releaseVersionCode = versionParts[0] * 1_000_000 + versionParts[1] * 1_000 + versionParts[2]

if (network != "local") {
    // A build for a real network carries that network's addresses or is not made at all: there is no fallback.
    require(deploymentFile.isFile) { "No ${deploymentFile.path}: a build for $network needs its deployment file" }
    val deployed = groovy.json.JsonSlurper().parse(deploymentFile).let { (it as Map<*, *>)["version"] as String? }
    require(deployed == releaseVersion) {
        "${deploymentFile.name} is release '$deployed' and VERSION says '$releaseVersion': the app must be built at the release it is published as"
    }
} else if (deploymentFile.isFile) {
    val deployed = groovy.json.JsonSlurper().parse(deploymentFile).let { (it as Map<*, *>)["version"] as String? }
    if (deployed != releaseVersion) logger.warn("deployments/local.json is release '$deployed', VERSION says '$releaseVersion': run make deploy")
}

// The release key, from the environment or ~/.gradle/gradle.properties, never from this repository. Without it a
// release build is unsigned, as CI makes it: the key is used in a ceremony, on hardware (apps/android/README.md).
fun secret(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() } ?: (findProperty(name) as String?)?.takeIf { it.isNotBlank() }
val releaseKeystore = secret("KLIPPELGO_RELEASE_KEYSTORE")

android {
    namespace = "org.klippelgo.app"
    // The Valhalla library asks for 36 to compile against. The app still targets 35.
    compileSdk = 36

    defaultConfig {
        applicationId = "org.klippelgo.app"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseVersion

        // The network this build is pinned to. Only the local one may fall back to a fresh testnet's addresses.
        buildConfigField("String", "NETWORK", "\"$network\"")
        buildConfigField("boolean", "LOCAL_NETWORK", (network == "local").toString())
        // Where updates are downloaded from. Empty: the mirror next to the chain node, where the map packs are.
        buildConfigField("String", "APK_MIRROR", "\"${(findProperty("klippelgo.apkMirror") as String?).orEmpty()}\"")

        // No tests run on the phone. This is what starts the UI agent the test harness talks to (tests/README.md).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The core is built for these two only (cargoNdk below). MapLibre and Valhalla also bring x86 and 32-bit
        // ARM, which would only add 37 MB to an app that cannot start there.
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = secret("KLIPPELGO_RELEASE_STORE_PASSWORD")
                keyAlias = secret("KLIPPELGO_RELEASE_KEY_ALIAS")
                keyPassword = secret("KLIPPELGO_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // Reproducible: the same tag builds the same APK, signature aside, wherever it is built. Nothing
            // about the machine or the checkout goes in.
            vcsInfo.include = false
            // R8 drops the code and resources nothing reaches. What is found by reflection is kept in
            // proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // A release is one APK per architecture, each carrying only its own native libraries, plus a universal one.
    // Debug builds stay a single APK, which is what the scripts and the test harness install.
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any { it.contains("Release") }
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    // The dependency list Google Play reads is encrypted for Google alone: it would make the APK differ from what
    // anyone else can check, and says nothing a person can verify.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/deployments"))

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/DISCLAIMER",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/FastDoubleParser-*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/versions/9/module-info.class",
                "META-INF/io.netty.versions.properties",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Ships the network's addresses inside the app, as deployment.json. Only for the local network may the file be
// missing: the app then uses the addresses a fresh local testnet always gets.
val copyDeployment by tasks.registering(Sync::class) {
    from(deploymentFile) { rename { "deployment.json" } }
    into(layout.buildDirectory.dir("generated/deployments"))
    inputs.property("network", network)
}

// Builds the Rust core, which holds the gossip network and the map and trip proofs, for phones (arm64) and emulators (x86_64). Needs the NDK
// and `cargo install cargo-ndk`; scripts/two-phones.sh installs both.
val coreDir = rootProject.file("../../core")
val rustJniLibs = layout.buildDirectory.dir("rustJniLibs")
val cargoNdk by tasks.registering(Exec::class) {
    workingDir = coreDir
    environment("ANDROID_HOME", android.sdkDirectory.path)
    // Symbols are a third of the library and nothing on the phone reads them. Set here rather than in Cargo.toml
    // so the host build, whose library UniFFI reads, keeps them.
    environment("CARGO_PROFILE_RELEASE_STRIP", "symbols")
    // cargo ndk only adds files, so a library the core no longer builds would be packaged forever. It also copies
    // every shared library cargo built, such as if-watch's, which the core links statically and never loads.
    doFirst { delete(rustJniLibs) }
    doLast { delete(fileTree(rustJniLibs) { exclude("**/libklippelgo_core.so") }) }
    commandLine(
        "cargo", "ndk", "-t", "arm64-v8a", "-t", "x86_64", "-o", rustJniLibs.get().asFile.path,
        "build", "--release", "--lib",
    )
    inputs.dir(coreDir.resolve("src"))
    inputs.dir(coreDir.resolve("verify/src"))
    inputs.dir(coreDir.resolve("proofs/src"))
    inputs.files(
        coreDir.resolve("Cargo.toml"), coreDir.resolve("Cargo.lock"),
        coreDir.resolve("verify/Cargo.toml"), coreDir.resolve("proofs/Cargo.toml"),
    )
    outputs.dir(rustJniLibs)
}
android.sourceSets["main"].jniLibs.srcDir(rustJniLibs)

tasks.named("preBuild") { dependsOn(copyDeployment, cargoNdk) }

dependencies {
    implementation("org.klippelgo:chain") {
        // The chain module uses JNA's desktop jar to load the Rust core. Phones need the Android build.
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation("net.java.dev.jna:jna:5.17.0@aar")

    // The map: MapLibre draws the pack's vector tiles, Valhalla routes on its graph. Both run on the phone.
    implementation("org.maplibre.gl:android-sdk:13.6.1")
    implementation("io.github.rallista:valhalla-mobile:0.6.4")
    implementation("io.github.rallista:valhalla-models:0.6.0")
    implementation("io.github.rallista:valhalla-models-config:0.6.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // The UI agent: it reads the screen and injects touches for the test harness. It is in the test APK only.
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
