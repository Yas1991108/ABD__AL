pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx (Whisper ASR + Piper TTS engine) isn't published to
        // Maven Central — its own repo builds/publishes via JitPack, which
        // is the officially documented way to consume it from Gradle:
        // https://github.com/k2-fsa/sherpa-onnx/blob/master/jitpack.yml
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "LamdaAI"
include(":app")
