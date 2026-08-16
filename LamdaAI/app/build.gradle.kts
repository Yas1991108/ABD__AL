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

    // --------------------------------------------------------------------
    // Offline speech engine (voice/VoiceManager.kt): wraps a Whisper ONNX
    // export for speech-to-text and a Piper/VITS ONNX voice for
    // text-to-speech, both running on-device via onnxruntime.
    //
    // sherpa-onnx is NOT reliably resolvable through JitPack — JitPack has
    // to build this (large, NDK-based) library from source on first
    // request, and that build frequently fails/times out on JitPack's
    // shared infrastructure. Instead we pull the prebuilt .aar straight
    // from the project's own GitHub Releases (this is k2-fsa's own
    // documented distribution method) and reference it as a local file.
    // The CI workflow downloads this file into app/libs/ BEFORE this
    // build script runs — see .github/workflows/main.yml, step
    // "Download sherpa-onnx AAR".
    //
    // NOTE: sherpa-onnx.aar already bundles its own libonnxruntime.so —
    // do NOT also add a separate onnxruntime-android dependency here, it
    // causes a duplicate .so file conflict at packaging time
    // (":app:mergeDebugNativeLibs" fails with "2 files found with path
    // lib/arm64-v8a/libonnxruntime.so").
    // --------------------------------------------------------------------
    implementation(files("libs/sherpa-onnx.aar"))
}
</parameter>
