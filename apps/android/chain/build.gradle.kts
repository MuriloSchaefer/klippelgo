plugins {
    kotlin("jvm") version "2.1.0"
    `java-library`
}

group = "org.klippelgo"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// The gossip network lives in the Rust core. Build it for this computer and generate its Kotlin bindings with
// UniFFI, so the client and its tests run on a plain JVM. The Android app builds the same core for phones.
val coreDir = file("../../../core")
val coreLibrary = coreDir.resolve("target/release/" + System.mapLibraryName("klippelgo_core"))
val uniffiOut = layout.buildDirectory.dir("generated/uniffi")

val cargoBuildHost by tasks.registering(Exec::class) {
    workingDir = coreDir
    commandLine("cargo", "build", "--release", "--lib")
    inputs.dir(coreDir.resolve("src"))
    inputs.files(coreDir.resolve("Cargo.toml"), coreDir.resolve("Cargo.lock"), coreDir.resolve("uniffi.toml"))
    outputs.file(coreLibrary)
}

val uniffiBindings by tasks.registering(Exec::class) {
    dependsOn(cargoBuildHost)
    workingDir = coreDir
    commandLine(
        "cargo", "run", "--release", "--bin", "uniffi-bindgen", "--", "generate", "--library", coreLibrary.path,
        "--language", "kotlin", "--no-format", "--out-dir", uniffiOut.get().asFile.path,
    )
    inputs.file(coreLibrary)
    outputs.dir(uniffiOut)
}

kotlin.sourceSets.main {
    kotlin.srcDir(uniffiBindings.map { uniffiOut.get() })
}

dependencies {
    api("org.web3j:core:4.13.0")
    // Loads the Rust core. The Android app swaps this jar for JNA's Android build.
    api("net.java.dev.jna:jna:5.17.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("jna.library.path", coreLibrary.parentFile.path)
    // The two-phone ride test runs only when a local testnet is up (see testnet/README).
    environment("KLIPPELGO_RPC", System.getenv("KLIPPELGO_RPC") ?: "")
    environment("KLIPPELGO_DEPLOYMENT", System.getenv("KLIPPELGO_DEPLOYMENT") ?: "")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
