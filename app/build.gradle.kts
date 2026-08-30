import java.util.Properties

plugins {
    // AGP 9 has built-in Kotlin support; the kotlin.android plugin must NOT be applied.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing material is kept out of the repository; without it, only debug builds work.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

android {
    namespace = "com.freeftp.app"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.freeftp.app"
        minSdk = 26
        targetSdk = 36
        // Bump both per release and tag the commit to match (versionName "1.7" ->
        // tag "v1.9"). Kept as literals here rather than behind a constant: F-Droid's
        // update checker reads these values straight out of defaultConfig, and cannot
        // resolve a reference.
        versionCode = 10
        versionName = "1.9"
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // v2 alone is enough to install on Android 7+, but v3 records the
                // signing lineage, which is what makes a future key rotation possible.
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    // AGP attaches an encrypted list of dependencies to the APK signing block for
    // Google Play's benefit. F-Droid's scanner rejects it as an extra signing block,
    // and it is opaque data no user of this app has any use for.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        release {
            // AGP embeds the git commit and working-tree state in
            // META-INF/version-control-info.textproto. It differs between a developer's
            // checkout and a fresh clone of the same commit, which is enough on its own
            // to break a reproducible build. It buys us nothing, so leave it out.
            vcsInfo { include = false }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
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
    implementation(project(":core"))
    implementation(libs.slf4j.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
