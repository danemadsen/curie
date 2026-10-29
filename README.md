<p align="center">
  <img src="docs/icon-512.png" width="112" alt="Curie's icon: the corona of a solar eclipse">
</p>

# Curie

Curie is an Android assistant that runs Liquid AI's
[LFM3-Omni-2B](https://huggingface.co/LiquidAI/LFM3-Omni-2B-ONNX) on the phone with
[ONNX Runtime](https://onnxruntime.ai). It understands text, photos, videos and speech, and
answers in text, in speech with its own voice, or in both at once.

## Features

- **Text, photos, videos and voice in.** A message can combine text with images, video clips
  (with their soundtrack) and recorded speech.
- **Text and speech out.** Curie answers in text, in speech, or in text and speech interleaved,
  streamed as the model generates them. A bundled LoRA adapter gives the speech the Curie voice.

In development (these may be missing from current builds):

- **Phone-call mode**: a spoken back-and-forth with Curie, like a phone call.
- **"Hey Curie"**: an optional wake phrase that starts Curie hands-free.
- **Default assistant**: Curie can be chosen as the phone's digital assistant and started with
  the assist gesture.
- **Assistant actions**: Curie acts on the phone through the model's tool calls, for example to
  play music, start navigation, set alarms and timers or open apps.

## How it works

### The model is downloaded at runtime

The app package does not contain the model. Curie downloads the q4 CPU bundle of
[LiquidAI/LFM3-Omni-2B-ONNX](https://huggingface.co/LiquidAI/LFM3-Omni-2B-ONNX): the weights of
`decoder_q4` and the float32 `depth_decoder` (their graphs come from the app, see below), the
`vision_encoder_q8`, `audio_encoder` and `codec_decoder` graphs with their weights, and
`tokenizer.json`: 9 files, 2.93 GB in all.

The download is pinned to revision `7d6bd3fd59a0622e5ce7fefc141145a8bba1435a`:
[`ModelFiles.kt`](engine/src/main/kotlin/com/danemadsen/curie/engine/ModelFiles.kt) lists every file
with its size and SHA-256, and each download is verified against them. To move to another
revision, update `REVISION` and every size and SHA-256 together, update `MODEL_REVISION` and
`GRAPH_FILES` in `tools/lora/build-lora-assets.py` to match, and rebuild the LoRA assets (below).

### The Curie voice is bundled

[`app/src/main/assets/lora`](app/src/main/assets/lora) holds the voice, 68.5 MB in four files:

| File | What it is |
| --- | --- |
| `decoder_q4.lora.onnx`, `depth_decoder.lora.onnx` | The model's `decoder_q4` and `depth_decoder` graphs (no weights) with a LoRA branch added to every adapted projection |
| `decoder_q4.onnx_adapter`, `depth_decoder.onnx_adapter` | The LoRA matrices, a PEFT adapter trained on [LFM3-Audio-2B](https://huggingface.co/LiquidAI/LFM3-Audio-2B), whose language model and depth decoder LFM3-Omni-2B shares |

ONNX Runtime applies the adapter at run time (`OrtLoraAdapter` and
`RunOptions.addActiveLoraAdapter`); without it, the patched graphs compute the base model. The
patched graphs read the downloaded weights, and ONNX Runtime only loads external weights from a
graph's own folder, so the app copies the assets next to the downloaded `*.onnx_data` files. The
APK stores them uncompressed, which makes that a plain copy.

The assets are regular git files, not Git LFS: the largest, `decoder_q4.onnx_adapter`, is 64 MB,
under GitHub's 100 MB file limit (pushing it shows GitHub's warning for files over 50 MB).
`.gitattributes` marks them binary, so git never diffs or merges them. Each rebuild adds about
68 MB to the repository's history for good, so rebuild them only when the adapter or the model
revision changes.

To rebuild them you need the PEFT adapter folder (`adapter_config.json` and
`adapter_model.safetensors`), which is not in the repository; `tmp/` is ignored by git:

```sh
uv run tools/lora/build-lora-assets.py \
    --adapter-dir tmp/lfm3-audio-curie-lora \
    --out-dir app/src/main/assets/lora
```

The script downloads the two graph files at the pinned revision (checking their SHA-256) into
`build/lora-graphs`, patches them and writes the adapters.

To verify them against a local copy of the export (see
[A local copy of the model](#a-local-copy-of-the-model)):

```sh
uv run tools/lora/verify-lora-assets.py \
    --model-dir /path/to/LFM3-Omni-2B-ONNX \
    --assets-dir app/src/main/assets/lora \
    --adapter-dir tmp/lfm3-audio-curie-lora
```

It checks that the patched graphs compute the original model without the adapter, that the
patched depth decoder with the adapter matches the original one with the LoRA merged into its
weights, and that the adapter changes the decoder's output.

## Architecture

| Path | Contents |
| --- | --- |
| [`engine/`](engine) | The whole LFM3-Omni inference stack as a plain Kotlin/JVM library without Android APIs, so it builds and tests on any JVM. It compiles against the ONNX Runtime Java API (`ai.onnxruntime`) without bundling it: the app ships `onnxruntime-android`, the tests run the desktop `onnxruntime`. |
| [`app/`](app) | The Android app: Kotlin, Jetpack Compose and Material 3, with a look based on the Maid app. It depends on `:engine` and `onnxruntime-android`. |
| [`tools/`](tools) | Python helper scripts, run with `uv run` (their dependencies are declared inline, PEP 723). |
| [`art/`](art) | The icon's source art, `eclipse.svg`, and `render-icon.py`, which renders the launcher icon resources to [`docs/icon-512.png`](docs/icon-512.png) for the store. |

The engine's public API:

- `OmniEngine` opens `Conversation`s, each with its own KV cache, so a short-lived one (text to
  speech) does not evict a chat's. A conversation keeps the longest cached prefix of each new
  prompt, because prefill dominates the time on a phone.
- `Conversation.generate` takes a `Prompt` and a `GenerationRequest` (text, automatic, speech or
  interleaved mode) and streams the answer to a `GenerationListener`; `Conversation.prefill` fills
  the cache ahead of time, for example with the system prompt.
- `SpeechDecoder` turns speech frames into 24 kHz audio as they arrive.
- `ChatMessage` and `ContentPart` describe the conversation, `Lfm3Omni` holds the model's constants,
  `ModelFiles` the pinned download, and `MediaGeometry` the image, video and audio sizing of the
  original Python processors.

## Building

You need:

- JDK 17
- The Android SDK with platform 37 (`platforms;android-37.0`) and build tools 36.0.0, the version
  the Android Gradle Plugin 9.5 uses

The Gradle wrapper brings Gradle itself.

```sh
./gradlew :app:assembleDebug
```

The build splits by ABI: `app/build/outputs/apk/debug/` gets an `arm64-v8a` APK, an `x86_64` APK
and a universal APK with both. Curie is 64-bit only, because a 3 GB model needs a 64-bit process.
It supports Android 10 (API 29) and later.

The version code is the number of commits (`git rev-list --count HEAD`), so every build of `main`
installs over the previous one; the version name is set in `app/build.gradle.kts` (see
[Releases](#releases)).

## Signing

Release builds read `key.properties` from the repository root:

```properties
signingKey=/path/to/signing.jks
uploadKey=/path/to/upload.jks
storePassword=...
keyPassword=...
keyAlias=...
```

- `signingKey` is the app signing keystore, which signs the APKs; `uploadKey` is the Google Play
  upload keystore, which signs the app bundle. Both use the same alias and passwords.
- Paths are absolute or relative to the repository root. The file uses the Java properties
  format, so a backslash in a value is written `\\`.
- **Never commit `key.properties` or a keystore.** Git ignores `key.properties`, `*.jks` and
  `*.keystore`.

```sh
./gradlew :app:assembleRelease                       # APKs, signed with signingKey
./gradlew :app:bundleRelease -Pcurie.uploadKey=true  # The Play bundle, signed with uploadKey
```

Keep these two commands separate: a Gradle run that builds a bundle turns the ABI splits off,
because the Android Gradle Plugin cannot build a bundle with them on. Without `key.properties`,
release builds are unsigned.

## Testing

```sh
./gradlew :engine:test :app:testDebugUnitTest :app:lintDebug
```

These tests need no model, and CI runs the same command.

### A local copy of the model

`tools/lora/verify-lora-assets.py` needs a local copy of the export at the pinned revision, in
the Hugging Face layout: `tokenizer.json` and the configuration files next to an `onnx/` folder
with the q4 bundle. To download it (2.9 GB):

```sh
uvx --from huggingface_hub hf download LiquidAI/LFM3-Omni-2B-ONNX \
    --revision 7d6bd3fd59a0622e5ce7fefc141145a8bba1435a \
    --include "*.json" --include "*.jinja" \
    --include "onnx/decoder_q4.onnx*" --include "onnx/depth_decoder.onnx*" \
    --include "onnx/vision_encoder_q8.onnx*" --include "onnx/audio_encoder.onnx*" \
    --include "onnx/codec_decoder.onnx*" \
    --local-dir /path/to/LFM3-Omni-2B-ONNX
```

### Model integration tests

These tests are in development, so this part is provisional. The engine's tests that run the
model will read it from the folder that `CURIE_MODEL_DIR` names, and are skipped when it is not
set; their JVM may use up to 6 GB of heap.

```sh
CURIE_MODEL_DIR=/path/to/model ./gradlew :engine:test
```

The layout that folder needs is not settled yet: either the download above as it is, or the flat
layout the app uses on the phone, with the LoRA assets next to the downloaded weights.

## Continuous integration

Two workflows run on GitHub Actions:

| Workflow | Runs on | Does |
| --- | --- | --- |
| [**Test**](.github/workflows/test.yml) | Pull requests to `main`, pushes to `main`, manual runs | Unit tests and lint |
| [**Build**](.github/workflows/build.yml) | Pushes to `main`, manual runs | Signed release APKs and Play bundle, uploaded as artifacts |

Neither publishes anything: a release is made by hand from a build's artifacts. A new push to a
pull request cancels the tests still running for the old one, and a new push to `main` cancels the
build still running for the old one. When tests or lint fail, their reports are kept for seven days.

The build checks every signature (`apksigner` for the APKs, `jarsigner` for the bundle) before
uploading:

| Artifact | File |
| --- | --- |
| `curie-android-arm64-v8a` | `curie-android-arm64-v8a.apk` |
| `curie-android-x86_64` | `curie-android-x86_64.apk` |
| `curie-android-universal` | `curie-android-universal.apk` |
| `curie-android-bundle` | `curie-android-bundle.aab`, signed with the upload key |

Artifacts count against the Actions storage in the repository owner's plan: 500 MB on GitHub Free
and 1 GB on Pro, shared by the owner's private repositories and accrued hourly. One build's files
take about 340 MB, so the build keeps them for one day, long enough to download them
(`ARTIFACT_RETENTION_DAYS` in the workflow); at that rate GitHub Free's storage covers up to about
45 builds a month.

### Repository secrets

The build needs these secrets (*Settings → Secrets and variables → Actions*):

| Secret | Value |
| --- | --- |
| `ANDROID_SIGNING_KEYSTORE` | The app signing keystore, base64 encoded |
| `ANDROID_UPLOAD_KEYSTORE` | The upload keystore, base64 encoded |
| `ANDROID_STORE_PASSWORD` | The keystores' password |
| `ANDROID_KEY_PASSWORD` | The keys' password |
| `ANDROID_KEY_ALIAS` | The keys' alias |

With the GitHub CLI (it prompts for the values that are not piped in):

```sh
base64 < signing.jks | gh secret set ANDROID_SIGNING_KEYSTORE --repo danemadsen/curie
base64 < upload.jks | gh secret set ANDROID_UPLOAD_KEYSTORE --repo danemadsen/curie
gh secret set ANDROID_STORE_PASSWORD --repo danemadsen/curie
gh secret set ANDROID_KEY_PASSWORD --repo danemadsen/curie
gh secret set ANDROID_KEY_ALIAS --repo danemadsen/curie
```

The workflow decodes the keystores into the runner's temporary folder, writes `key.properties`
for the build and never prints a secret. Pull requests never reach the signing steps.

## Licenses

- **The model and the Curie voice**: LFM3-Omni-2B and LFM3-Audio-2B are licensed by Liquid AI
  under the [LFM Open License v1.0](app/src/main/assets/licenses/lfm-open-license-1.0.txt), and
  the bundled LoRA assets derive from them. The license does not cover commercial use by a
  company or other legal entity with annual revenue of 10 million US dollars or more (section 5).
- **Libraries**: [`app/src/main/assets/licenses`](app/src/main/assets/licenses) ships inside the
  app: [`notices.txt`](app/src/main/assets/licenses/notices.txt) lists the model and every
  library in the release build with its license (Apache 2.0, MIT, BSD 3-Clause or MPL 2.0), and
  the license texts sit next to it, including ONNX Runtime's third-party notices.
- **Curie itself**: the repository has no license file yet.
