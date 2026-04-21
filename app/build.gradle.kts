plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.sentryreplayrepro"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.sentryreplayrepro"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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
}

dependencies {
    // Pin to exactly 8.38.0 — matches customer's version from their replay JSON
    implementation("io.sentry:sentry-android:8.38.0")
}
