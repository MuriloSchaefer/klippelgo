plugins {
    kotlin("jvm") version "2.3.0"
    application
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

val repo = rootDir.parentFile
// The Rust core, built for this computer by the chain client's build.
val coreLibraries = repo.resolve("core/target/release")

// The folders are the layout: the harness, then one folder per kind of test.
kotlin.sourceSets.main {
    kotlin.srcDir("harness")
    // The names of the app's UI pieces, compiled from the app's own file so the two cannot drift apart.
    kotlin.srcDir(repo.resolve("apps/android/app/src/main/kotlin/org/klippelgo/app/ui/tags"))
}
kotlin.sourceSets.test {
    kotlin.srcDirs("integrity", "security", "performance")
}

dependencies {
    implementation("org.klippelgo:chain")
    implementation(kotlin("reflect"))
    // web3j logs through SLF4J. With nothing to log to, it says so on every command; this keeps it quiet.
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")
    implementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

application {
    mainClass.set("org.klippelgo.tests.cli.CliKt")
    applicationName = "klippelgo"
    applicationDefaultJvmArgs = listOf("-Djna.library.path=${coreLibraries.path}", "-Dklippelgo.root=${repo.path}")
}

fun Test.againstTheStack() {
    useJUnitPlatform()
    systemProperty("jna.library.path", coreLibraries.path)
    systemProperty("klippelgo.root", repo.path)
    // One phone, one chain: never two tests at once. And the world outside changes, so a run is never up to date.
    maxParallelForks = 1
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.test { againstTheStack() }

// `gradlew integrity`, `gradlew security`, `gradlew performance`: one kind of test at a time.
for (suite in listOf("integrity", "security", "performance")) {
    tasks.register<Test>(suite) {
        group = "verification"
        description = "Runs the tests in tests/$suite against the running stack."
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        againstTheStack()
        filter { includeTestsMatching("org.klippelgo.tests.$suite.*") }
    }
}
