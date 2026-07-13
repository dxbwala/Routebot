plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

/**
 * Release signing is configured entirely from environment variables so the real keystore and
 * its credentials are never written into the repo or Gradle files:
 *
 *   ROUTEBOT_KEYSTORE_PATH      absolute path to the .jks/.keystore file
 *   ROUTEBOT_KEYSTORE_PASSWORD  keystore password
 *   ROUTEBOT_KEY_ALIAS          key alias inside the keystore
 *   ROUTEBOT_KEY_PASSWORD       key password (often same as keystore password)
 *
 * CI (see .github/workflows/android-release.yml) decodes the keystore from a base64 secret and
 * sets these before running `bundleRelease` / `assembleRelease`. Locally, export the same four
 * variables yourself (e.g. in your shell profile, never in a committed file) to produce a signed
 * release build. Without them, `release` builds remain unsigned — safe for local experimentation,
 * but not distributable.
 */
val releaseKeystorePath = System.getenv("ROUTEBOT_KEYSTORE_PATH")
val releaseKeystorePassword = System.getenv("ROUTEBOT_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ROUTEBOT_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ROUTEBOT_KEY_PASSWORD")
val hasReleaseSigning = !releaseKeystorePath.isNullOrBlank() &&
    !releaseKeystorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.routedns.routebot"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.routedns.routebot"
        minSdk = 26
        targetSdk = 35
        versionCode = (project.findProperty("routebotVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = project.findProperty("routebotVersionName") as String? ?: "1.0.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)

    implementation(libs.okhttp)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode)

    debugImplementation(libs.androidx.compose.ui.tooling.preview)
}
