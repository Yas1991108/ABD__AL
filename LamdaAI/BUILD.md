# Building LamdaAI to its full ("bundled") footprint

> **Note:** the `.github/workflows/build.yml` CI workflow described below
> has been removed from this copy at the requester's wish (they're adding
> their own workflow file manually). Everything else in this document
> still describes how the pieces fit together — just re-add a workflow
> that does the same three jobs (checkout submodules, download models into
> `app/src/main/assets/`, run `./gradlew assembleDebug`) to get the same
> result. See "One-time setup" below for what your workflow needs to do.

This repo is designed to build a **fully self-contained APK**: a CI
workflow downloads the LLM, speech-recognition, and text-to-speech model
weights and bakes them straight into `app/src/main/assets/` *before*
compiling, so the APK that comes out the other end works immediately after
install — no "download model" step, no extra setup, nothing for the user
to do afterward.

Two things happen in the same CI run now:

1. **The native/library layer** — llama.cpp (compiled via NDK/CMake) plus
   sherpa-onnx (Whisper ASR + Piper TTS, pulled in via JitPack) — ends up
   *inside* the APK. ~30-40MB.
2. **The model files** (SmolLM2 GGUF, Whisper tiny.en, a Piper voice) are
   downloaded by the **workflow itself**, copied into
   `app/src/main/assets/`, and packaged into the APK by Gradle. ~420MB.
   On first app launch, `AssetModelInstaller.kt` unpacks them from the APK
   into internal storage (a few seconds, shown as a short "Setting up
   LamdaAI…" screen) — after that, everything runs fully offline.

Expect a debug APK in the neighborhood of **500-560MB**. That's close to,
but not exactly, the original 590MB estimate — some pieces (a Q4_K_M
SmolLM2 at ~271MB rather than 290MB, an int8 Whisper tiny.en at ~117MB
rather than the ggml 75MB tiny model, a low-quality ~30MB Piper voice) are
real, verified, currently-available files rather than the exact ones
originally sketched out, chosen to land in the same size class while
being things I could actually confirm exist and work together.

## One-time setup

1. Push this repo to GitHub if you haven't already.
2. Add the llama.cpp submodule — either run the helper script:
   ```bash
   ./scripts/setup-llama-cpp.sh          # defaults to tag b4200
   git add .gitmodules app/src/main/cpp/llama.cpp
   git commit -m "Add llama.cpp submodule"
   git push
   ```
   or do it by hand:
   ```bash
   git submodule add https://github.com/ggerganov/llama.cpp app/src/main/cpp/llama.cpp
   cd app/src/main/cpp/llama.cpp
   git checkout b4200   # or any recent release tag — see note below
   cd -
   git add .gitmodules app/src/main/cpp/llama.cpp
   git commit -m "Add llama.cpp submodule"
   git push
   ```
   (`.gitmodules` is already in the repo pointing at the right path/URL —
   you're just pinning which commit it checks out.)
3. Add your own CI workflow (or build locally with Android Studio + NDK
   26.3.11579264). Whatever you use needs to, in order: check out the repo
   **with submodules**, install the NDK, download the three model files
   into `app/src/main/assets/models|asr|tts` (see `AssetModelInstaller.kt`
   for the exact expected paths/filenames), then run
   `./gradlew assembleDebug`.
4. Once built, download/copy off the resulting `app-debug.apk` from
   `app/build/outputs/apk/debug/`.
5. Sideload it onto a device (`adb install app-debug.apk`, or copy it over
   and open it directly — you'll need to allow installs from unknown
   sources). Android will warn you it's a large app before installing;
   that's expected.

## Fixes applied to this copy

- `ModelManager.kt`: filled in a verified SHA-256 for the Whisper
  `ggml-tiny.bin` fallback download (confirmed against its Hugging Face
  file page). The Piper voice entry had a **non-functional placeholder
  URL** (`"REPLACE_WITH_CHOSEN_PIPER_VOICE_URL"`, not a real link at all)
  — replaced with the real `rhasspy/piper-voices` source for the "amy low"
  voice used elsewhere in this project.
- The SmolLM2 GGUF and Piper voice SHA-256 values are still placeholders
  — multiple re-uploads/re-quantizations of "the same" file exist across
  different HF repos with different bytes, so guessing a hash here is
  riskier than leaving it unverified (a wrong hash hard-fails every
  download; a missing one just logs a warning and skips verification).
  Grab the real value from each file's HF page before you rely on it.
- Added `scripts/setup-llama-cpp.sh` so the submodule setup is one command
  instead of copy/pasting from this doc.

## First run on a device

- App installs at its full bundled size — nothing to download afterward.
- On first launch, you'll see "Setting up LamdaAI…" for a few seconds
  while `AssetModelInstaller` copies the GGUF/ONNX files out of the APK
  into internal storage (needed because llama.cpp/onnxruntime mmap real
  files, not zipped APK assets). Every launch after that skips straight to
  the chat list.
- Chat works immediately with real llama.cpp inference (SmolLM2 360M).
- Tap the mic icon in the chat input bar for push-to-talk voice input
  (Whisper); tap the speaker icon on any assistant reply to hear it read
  aloud (Piper). First use of the mic will prompt for the microphone
  permission.

## If a model download step fails in CI

The workflow's "Download bundled model assets" step pulls from Hugging
Face and GitHub Releases. If one of those URLs 404s (upstream repos do
occasionally reorganize files), the fix is almost always: open the failing
URL in a browser, find where the file moved to, and update the
corresponding `curl` line in `.github/workflows/build.yml` — the rest of
the pipeline doesn't need to change.

## If the native build fails

The JNI code in `app/src/main/cpp/llama-jni.cpp` is written against a
fairly recent llama.cpp release. If you pin a different/newer tag and the
GitHub Actions build fails on a compile error, it's almost always a
function that got renamed in `llama.h` between releases (e.g.
`llama_load_model_from_file` → `llama_model_load_from_file` was one such
rename upstream made at one point) — open `llama.h` in the submodule and
match the new name, the surrounding logic doesn't need to change.

sherpa-onnx (Whisper/Piper) is pulled from JitPack
(`com.github.k2-fsa:sherpa-onnx`) rather than Maven Central — if that
build ever fails, check https://jitpack.io/#k2-fsa/sherpa-onnx for the
latest available tag and update the version in `app/build.gradle.kts`.

## I haven't been able to compile-test this

I don't have an Android SDK/NDK in the environment I write this in, so I
can't run `./gradlew assembleDebug` myself to confirm this compiles
cleanly end-to-end — GitHub Actions running the workflow is the first
real compile. The pieces I could verify externally (the download URLs,
the JitPack coordinate, the sherpa-onnx Kotlin API shapes) are real and
checked against current sources; the risk that remains is mostly in exact
API details (e.g. `OfflineWhisperModelConfig` field names) that could have
shifted slightly between sherpa-onnx versions. If the first CI run fails
on a Kotlin compile error in `voice/VoiceManager.kt`, that's the most
likely place — paste the error back and it's a quick fix.

## Why the size differs from the original 590MB estimate

- SmolLM2 Q4_K_M: 271MB (close to, not exactly, the original 290MB figure)
- Whisper tiny.en int8: ~117MB (real download; ggml.cpp's own 75MB "tiny"
  quantization is a different toolchain than what sherpa-onnx uses — see
  above for why sherpa-onnx was used instead of a separate whisper.cpp JNI)
- Piper voice (amy, low quality): ~30MB
- App shell + Compose + ML Kit OCR + native libs: ~40-60MB

## Next steps not covered by this drop

- A release/signing config for `assembleRelease` once you want to
  distribute this beyond your own devices
- Trying a higher-quality Piper voice (medium/high, 60-100MB) if the low
  voice sounds too robotic — swap the URL in build.yml and the file names
  in VoiceManager.kt/AssetModelInstaller.kt together
- Wake-word / always-listening mode (currently push-to-talk only)

