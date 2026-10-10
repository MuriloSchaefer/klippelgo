plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.klippelgo.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.klippelgo.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
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

// Ships the addresses from the last `make deploy` (deployments/local.json) inside the app. Without that file the
// app falls back to the addresses a fresh local testnet always gets.
val copyDeployment by tasks.registering(Copy::class) {
    from(rootProject.file("../../deployments")) { include("local.json") }
    into(layout.buildDirectory.dir("generated/deployments"))
}

// Builds the Rust core, which holds the gossip network, for phones (arm64) and emulators (x86_64). Needs the NDK
// and `cargo install cargo-ndk`; scripts/two-phones.sh installs both.
val coreDir = rootProject.file("../../core")
val rustJniLibs = layout.buildDirectory.dir("rustJniLibs")
val cargoNdk by tasks.registering(Exec::class) {
    workingDir = coreDir
    environment("ANDROID_HOME", android.sdkDirectory.path)
    commandLine(
        "cargo", "ndk", "-t", "arm64-v8a", "-t", "x86_64", "-o", rustJniLibs.get().asFile.path,
        "build", "--release", "--lib",
    )
    inputs.dir(coreDir.resolve("src"))
    inputs.files(coreDir.resolve("Cargo.toml"), coreDir.resolve("Cargo.lock"))
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
}
