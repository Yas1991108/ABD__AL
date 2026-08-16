plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.lamdaai.app"
    compileSdk = 35
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.lamdaai.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-skeleton"

        // arm64-v8a covers the vast majority of devices sold since ~2017.
        // Add "armeabi-v7a" here if you need to support older/budget
        // hardware — it roughly doubles native build time and APK variants.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
    }

    // GGUF/ONNX/whisper model assets must stay byte-identical (mmap'd by
    // llama.cpp / onnxruntime at word-aligned offsets) — if AAPT compresses
    // them into the APK, loading them crashes with a SIGBUS on some devices.
    androidResources {
        noCompress += listOf("gguf", "onnx", "bin", "tflite")
    }

    // The bundled model assets alone (GGUF + Whisper + Piper) are ~470MB
    // uncompressed, so let R8/resource shrinking run even on debug builds —
    // otherwise debug artifacts balloon further with unused code paths.
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
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
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")

    // Local persistence (chat list, messages)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.compose.material:material-icons-extended:1.6.8")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Overlay bubble (OverlayService) hosts a ComposeView outside any
    // Activity, so it has to supply its own Lifecycle/ViewModelStore/
    // SavedStateRegistry owners — these are what makes that possible.
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.2")

    // On-device (not Play-Services-downloaded) text recognition for the
    // MediaProjection screenshot -> OCR pipeline (screen/OcrEngine.kt).
    // Ships its model inside the app, so it runs fully offline.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Used directly by LlamaCppBridge/ModelManager (callbackFlow, flow{},
    // withContext) — was previously only pulled in transitively via Room.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Offline speech engine (voice/VoiceManager.kt): wraps a Whisper ONNX
    // export for speech-to-text and a Piper/VITS ONNX voice for
    // text-to-speech, both running fully on-device via onnxruntime. This is
    // what backs "Whisper" and "Piper" in the original plan — rather than
    // hand-rolling separate JNI bridges for two different C++ projects, both
    // ride on this one maintained wrapper (bundled ASR/TTS model assets are
    // downloaded into app/src/main/assets/ by the CI workflow, see
    // .github/workflows/build.yml).
    implementation("com.github.k2-fsa:sherpa-onnx:v1.10.23")
}
