plugins {
    // AGP 9 has built-in Kotlin support; the kotlin.android plugin must NOT be applied.
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.freeftp.core"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/INDEX.LIST",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.commons.net)
    api(libs.sshj)
    // Pin BouncyCastle to the current release rather than the older one SSHJ
    // declares transitively.
    api(libs.bouncycastle.prov)
    api(libs.bouncycastle.pkix)
    api(libs.eddsa)
    api(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.slf4j.simple)
    testImplementation(libs.ftpserver.core)
    testImplementation(libs.mina.core)
    testImplementation(libs.sshd.core)
    testImplementation(libs.sshd.sftp)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Opt-in verbose protocol logging while diagnosing a test: -Pdebug-ssh
if (project.hasProperty("debug-ssh")) {
    tasks.withType<Test>().configureEach {
        systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug")
        systemProperty("org.slf4j.simpleLogger.logFile", "System.out")
        testLogging { showStandardStreams = true }
    }
}

/**
 * Runs the test suite's real FTP and SFTP servers on fixed ports, for driving the
 * Android app end to end against something that actually speaks the protocols.
 */
tasks.register<JavaExec>("devServers") {
    group = "verification"
    description = "Starts a real FTP (2121) and SFTP (2222) server over a scratch directory."
    dependsOn("compileDebugUnitTestKotlin")
    mainClass.set("com.freeftp.core.testing.DevServers")
    // Reuse the classpath AGP already assembled for the unit tests; resolving the
    // raw configuration here hits variant ambiguity between Android and JVM artifacts.
    val unitTests = tasks.named<Test>("testDebugUnitTest")
    classpath = files({ unitTests.get().classpath })
    systemProperty(
        "org.slf4j.simpleLogger.defaultLogLevel",
        (project.findProperty("logLevel") as String?) ?: "info",
    )
    args = listOf(
        (project.findProperty("serveDir") as String?) ?: "/tmp/freeftp-root",
        (project.findProperty("ftpPort") as String?) ?: "2121",
        (project.findProperty("sftpPort") as String?) ?: "2222",
    )
}
