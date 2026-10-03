import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jarvis.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        // L'app ne connaît plus les clés Groq/Gemini : elle ne parle qu'à ton backend Render.
        // Mets ça dans local.properties (jamais dans le code en dur / git) :
        // BACKEND_URL=https://jarvis-43io.onrender.com
        // APP_SHARED_SECRET=le_meme_secret_que_sur_render
        val localProps = Properties()
        val localFile = rootProject.file("local.properties")
        if (localFile.exists()) {
            localProps.load(localFile.inputStream())
        }
        buildConfigField("String", "BACKEND_URL", "\"${localProps.getProperty("BACKEND_URL", "https://jarvis-43io.onrender.com")}\"")
        buildConfigField("String", "APP_SHARED_SECRET", "\"${localProps.getProperty("APP_SHARED_SECRET", "")}\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Réseau (appels API Groq / Gemini)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Stockage chiffré des clés API saisies dans l'app
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")

    // Coroutines pour l'async
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Caméra (CameraX) pour capturer le flux vidéo de la caméra avant
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")

    // Rend le service "LifecycleOwner" (requis par CameraX pour attacher la caméra) —
    // implémenté manuellement via LifecycleRegistry, plus fiable que lifecycle-service.
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Suivi de la main (MediaPipe Hand Landmarker) : détecte la position des doigts en temps réel
    implementation("com.google.mediapipe:tasks-vision:latest.release")
}
