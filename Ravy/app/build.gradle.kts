plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ravy.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ravy.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-scaffold"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.foundation:foundation:1.6.8")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.8")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling:1.6.8")

    // Gmail: Google Sign-In only. Gmail API access itself is plain REST calls
    // (gmail/GmailClient.kt) — the official "google-api-client-android" +
    // "google-api-services-gmail" libraries were removed because their huge
    // generated code was pushing Kotlin compilation past what phone-based
    // builds (e.g. CodeAssist) can handle in memory, causing real build
    // failures. This keeps identical functionality with far less weight.
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // Encrypted local storage for the user's own LLM/TTS API keys
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
