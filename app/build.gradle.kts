plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.victorious.mesh"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.victorious.mesh"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // Fixed, committed debug keystore so every debug build (local + CI) shares
        // the same signing cert. The fleet pairs EXTERNAL_NODEs by package + cert.
        getByName("debug") {
            storeFile = file("keystore/victorious-mesh-debug.jks")
            storePassword = "android"
            keyAlias = "victoriousmeshdebug"
            keyPassword = "android"
        }
        // Release signing is read ONLY from environment variables / CI secrets.
        // No release keystore is ever committed to this repo.
        val releaseStore = System.getenv("VM_RELEASE_STORE_FILE")
        if (!releaseStore.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = System.getenv("VM_RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("VM_RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("VM_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
