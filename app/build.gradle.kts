plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val gitCommitCount = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
    .standardOutput.asText.map { it.trim().toIntOrNull() ?: 1 }
val gitDescribe = providers.exec { commandLine("git", "describe", "--tags", "--dirty", "--always") }
    .standardOutput.asText.map { it.trim().removePrefix("v").ifBlank { "0.0.0" } }

android {
    namespace = "io.github.superthom196.matv"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.superthom196.matv"
        minSdk = 28
        targetSdk = 36
        // Versions come from git, so every installed build says exactly what it is (see AGENTS.md):
        // versionName is the latest tag plus distance, e.g. "0.5.0" on the tagged commit and
        // "0.5.0-3-gabc1234-dirty" three commits later with local edits; versionCode is the commit
        // count, which only ever grows. Tag a release with `git tag vX.Y.Z` and the next build is it.
        versionCode = gitCommitCount.get()
        versionName = gitDescribe.get()
    }

    buildTypes {
        release {
            // Debug-signed so it side-loads like the debug build; minified so it starts fast on a slow TV CPU.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // lintVital crashes on this toolchain (missing IntelliJ mock classes); not needed for side-loading.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons)
    implementation(libs.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.core.ktx)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.datastore.preferences)
}
