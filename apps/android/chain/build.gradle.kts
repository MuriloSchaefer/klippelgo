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

dependencies {
    api("org.web3j:core:4.13.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // The two-phone ride test runs only when a local testnet is up (see testnet/README).
    environment("KLIPPELGO_RPC", System.getenv("KLIPPELGO_RPC") ?: "")
    environment("KLIPPELGO_DEPLOYMENT", System.getenv("KLIPPELGO_DEPLOYMENT") ?: "")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
