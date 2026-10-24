# Architecture

Panelglass is a single-activity Android app (Kotlin, Jetpack Compose, Hilt) split into Gradle modules by layer. This
document covers the module layout, the main components and how they talk, storage, concurrency and the security
model. The translation flow itself is in [FLOW.md](FLOW.md); detection and OCR in [DETECTION_AND_OCR.md](DETECTION_AND_OCR.md).

## Contents

- [Principles](#principles)
- [Modules](#modules)
- [Runtime components](#runtime-components)
- [Engines](#engines)
- [Storage](#storage)
- [Concurrency and memory](#concurrency-and-memory)
- [Security and privacy](#security-and-privacy)
- [Testing](#testing)

## Principles

1. **Translate what the user sees.** The reader never downloads page images. It snapshots the WebView's viewport,
   so it works on any site, whatever the site does with its images (canvas, blob URLs, scrambled tiles).
2. **One pipeline, one entry point.** `TranslationPipeline.translate` is the only way pixels become translations.
3. **Patches, not pages.** The output is a small WEBP per text region, positioned in the page's CSS pixels and drawn
   natively over the WebView, so it scrolls and zooms with the art. The page's DOM is never modified by the translator.
4. **The user's engine is the engine.** A failing engine surfaces a typed failure; it is never silently replaced by
   another provider.
5. **Nothing secret or personal in logs or in the repo.** API keys live only in an Android-Keystore-backed store.

## Modules

```mermaid
flowchart TD
    app[":app"]
    browser[":feature:browser"]
    library[":feature:library"]
    settings[":feature:settings"]
    studio[":feature:studio"]
    pipeline[":core:pipeline"]
    engine[":core:engine"]
    ocr[":core:ocr"]
    render[":core:render"]
    ui[":core:ui"]
    data[":core:data"]
    model[":core:model"]

    app --> browser & library & settings & studio
    browser --> pipeline & engine & ocr & ui & data
    studio --> pipeline & render & engine & ocr & ui & data
    settings --> engine & ocr & ui & data
    library --> ui & data
    pipeline --> engine & ocr & render & data
    render --> ocr
    engine --> data
    ocr --> data
    data --> model
    ui --> model
```

Every module also depends on `:core:model`; those edges are left out for readability.

| Module | Responsibility | Key types |
|---|---|---|
| `:core:model` | Pure Kotlin, no Android. The shared vocabulary. | `Lang`, `EngineId`, `EngineFailure`, `TextLine`, `TextRegion`, `RegionKind`, `TranslateConfig`, `Settings`, `Site`, `GlossaryEntry`, `Pronoun`, `SeriesNotes`, `TranslationNotes`, `UserModel`, `ModelSpec` |
| `:core:data` | Persistence, secrets, downloads, updates. | `PanelglassDb` (Room), `SettingsRepository` (DataStore), `SecureKeyStore`, `PatchCache`, `SystemDownloads`, `GlossaryRepository`, `AppUpdates` |
| `:core:engine` | Everything that turns source text into target text. | `TranslationEngine`, `EngineRegistry`, `EngineRetry`, `BatchingTranslator`, `LlmContract`, `GeminiEngine`, `CustomOpenAiEngine`, `GoogleTranslateEngine`, `LocalLlmEngine` / `LocalPrompt`, `SfxDictionary`, `UserModelLibrary`, `HfCatalog` |
| `:core:ocr` | Finding and reading text in a bitmap. | `ComicTextDetector`, `MissedTextDetector` (+ `DbPostProcess`), `MangaOcrRecognizer`, `MlKitCropRecognizer` (+ `HighContrast`), `RegionBuilder`, `RegionClassifier`, `RegionClusterer` |
| `:core:render` | Erasing source text and drawing the translation; the Studio's page layers. | `TextEraser`, `RegionRenderer`, `Placement`, `PatchEncoder`, `PageCleaner`, `StudioRenderer`, `SfxRenderer`, `StudioFonts` |
| `:core:pipeline` | Orchestration: stages, limits, watchdogs, cache. | `TranslationPipeline`, `RegionOfInterest`, `PageSource`, `PipelineDump`, `TileDetection` |
| `:core:ui` | Shared Compose components, theming, every UI string. | `PanelglassTheme`, `Tokens`, `EnginePicker`, `ApiKeyDialog`, `SeriesMemorySheet`, `FontBrowser` |
| `:feature:browser` | The reader: WebView, capture, overlay, page language, blocking. | `ReaderScreen`, `ReaderViewModel`, `ScreenTranslate`, `ContentChange`, `MangaWebViewClient`, `NewWindows`, `BlockList`, `PageScan`, `mt.js`, `pt.js` |
| `:feature:library` | Saved sites and history. | `LibraryScreen`, `HistoryScreen`, `LibraryViewModel` |
| `:feature:settings` | Languages, engines, sound effects, notes, models, fonts, themes, storage, updates. | `SettingsScreen`, `SettingsViewModel` |
| `:feature:studio` | Translating the user's own files: import, translate, review, clean, letter, export. | `StudioTranslator`, `ReviewViewModel`, `StudioImporter`, `StudioExporter` |
| `:app` | Activity, navigation graph, application class, update dialog. | `MainActivity`, `PanelglassApp`, `UpdateHost` |

Dependencies point downwards only: features never depend on each other, and `:core:model` depends on nothing.

## Runtime components

```mermaid
flowchart LR
    subgraph Reader["Reader (feature:browser)"]
        WV[WebView + mt.js bridge]
        VM[ReaderViewModel]
        OV[Patch overlay<br/>Compose layer]
        WC[MangaWebViewClient<br/>+ NewWindows]
    end
    subgraph Core
        TP[TranslationPipeline]
        DET[ComicTextDetector]
        OCR[manga-ocr / ML Kit]
        ENG[EngineRegistry<br/>→ engine]
        REN[RegionRenderer]
        PC[(PatchCache)]
    end
    BL[(Block lists)]

    WV -- PixelCopy snapshot<br/>+ viewportMap --> VM
    VM -- translate(bitmap, ROI) --> TP
    TP --> DET --> OCR
    TP --> ENG
    TP --> REN
    TP <--> PC
    TP -- patches --> VM --> OV
    WC -- request / navigation policy --> WV
    BL --> WC
```

- **`ReaderViewModel`** owns the reader session: the WebView itself (kept across trips to Settings through a
  `MutableContextWrapper`), the resolved `TranslateConfig`, the screen-translation session and its patches, and what
  triggers the next capture: a settled scroll, pages that finish downloading, a page turn without a scroll
  (`ContentChange`), a rotation. It keeps each patch anchored to its page image so it follows layout shifts.
- **`mt.js`** is injected into every page. It reports where the page's images are (with a key per image), how many
  are still loading, and which controls float over them (`viewportMap()`); it also hides full-screen interstitials
  and disables `window.open`. `mt.js` and `pt.js` go in at document start (`WebViewCompat.addDocumentStartJavaScript`),
  before any page script, and `__mt`/`__pt` are read-only frozen objects, so a page cannot stand in for the bridge.
  Replies over `MAX_BRIDGE_REPLY` characters are dropped unparsed.
- **`MangaWebViewClient`** blocks requests to hosts on the merged block lists and enforces the navigation policy
  (no cross-site redirects without a tap, no ad hosts as the main frame, only `mailto:`/`tel:`/`sms:` leave the
  app). **`NewWindows`** decides every new-window request: only a tapped same-site link opens, in the reader.
- **`EngineRegistry`** resolves the selected `EngineId` to an engine, or to a typed failure (`MissingKey`,
  `ModelMissing`, not offered). It never picks a different engine.
- **Two detectors.** `ComicTextDetector` (RT-DETR-v2, balloons and text) runs first; `MissedTextDetector`
  (PP-OCRv6 tiny) then finds text outside its boxes, mostly sound effects. See [DETECTION_AND_OCR.md](DETECTION_AND_OCR.md).
- **The series' glossary and notes** (`GlossaryRepository`, edited in the reader's *Glossary & pronouns* sheet) and
  Settings' translation notes are combined by the pipeline before every capture and sent with each engine call. See
  [TRANSLATION_QUALITY.md](TRANSLATION_QUALITY.md).
- **`AppUpdates`** asks GitHub for the latest release once a day (never in a debug build), downloads a newer APK
  through `SystemDownloads`, checks it against the release's `.sha256`, and hands it to Android's installer; the
  dialog is `UpdateHost` in `:app`.

## Engines

| Engine | `EngineId` | Where it runs | Reads the crop itself |
|---|---|---|---|
| Gemini (and Gemma via the Gemini API) | `GEMINI` | Google API, user key | Yes: one call reads and translates |
| Custom AI (any OpenAI-compatible server) | `CUSTOM` | The user's own server, user key | No |
| Google Translate | `GOOGLE` | On device (ML Kit translation) | No |
| Qwen 2.5 1.5B | `QWEN15_LOCAL` | On device (LiteRT-LM) | No |
| Gemma 4 E2B | `GEMMA4_LOCAL` | On device (LiteRT-LM), 8 GB RAM devices | No |

Claude, OpenAI, OpenRouter, DeepSeek, DeepL and Papago exist in code as `@PlannedEngine` placeholders: hidden from
the picker and refused by the registry.

- **Custom AI's address.** The user names the server's base address and the model in Settings; the key goes to the
  key store, the address to the settings store (it is not a secret). `CustomEndpoint` reduces the address to its
  base — a pasted `…/chat/completions` is stripped — and requires HTTPS; the engine then calls
  `<base>/chat/completions`. No version segment is ever appended. The request is text only: the on-device OCR reads
  the page, so no crop or montage is sent.

- **Retries.** Only transient failures are retried, on the same engine and model (`EngineRetry`): rate limits up to
  3 times, overload (HTTP 500/502/503/504/529) up to 2 times, back-off capped at 4 s.
- **Cloud contract.** LLM engines exchange a JSON array of `{i, text}` items (`LlmContract`); Gemini's reading mode
  returns `{i, source, text}`.
- **On-device contract.** The LiteRT-LM models share one KV cache between prompt and reply (4096 tokens), so they get
  compact numbered lines (`3: translation`) in chunks of at most 12 items, and the reply is streamed and cut as soon
  as every requested line is complete. Only one on-device model is loaded at a time (`LocalEngines`).
- **On-device GPU.** LiteRT-LM tries its GPU backend first and falls back to the CPU. The GPU backend uses the
  vendor's OpenCL library, which from Android 12 an app can load only if the manifest declares it
  (`<uses-native-library>` for `libOpenCL.so`, `libOpenCL-car.so`, `libOpenCL-pixel.so`, `libvndksupport.so`, all
  optional). Without them the GPU backend fails with "OpenCL not supported" and the models run on the CPU.
- **Qwen's backend** (`Settings.qwenBackend`, Settings › Models › *Qwen runs on*): Automatic (default) takes the CPU
  on phones under 6 GB of RAM (`DeviceMemory.qwenOnCpu`), the GPU otherwise; GPU or CPU can be forced. On the CPU
  Qwen is about 3× slower, but its weights stay a memory-mapped file the system can reclaim (~0.6 GB pinned instead
  of ~2.8 GB; see [MEMORY_USAGE.md](MEMORY_USAGE.md)). The choice is read at each load, and a model loaded on the
  other backend is reloaded on its next call.

## Storage

| What | Where | Notes |
|---|---|---|
| Sites, history (last 15), SFX translations, glossary (with pronouns), series notes, Studio library | Room `PanelglassDb`, version 5 | Every schema change bumps the version and adds a `Migration` (`MIGRATION_3_4`, `MIGRATION_4_5`) |
| Settings, translation notes, skipped update, last update check, the user's own model library, the Custom AI server address, the compute backend | DataStore | Includes remembered Gemini request options per model |
| API keys | `SecureKeyStore` | AES-GCM, key in the Android Keystore |
| Rendered patches | `PatchCache` (disk LRU) | Keyed by image hash, languages, engine, `PIPELINE_VERSION`, font, and a digest of the glossary and notes |
| Detector models | `assets/` | Bundled: comic detector 11 MB, PP-OCRv6 tiny 1.8 MB |
| LLMs, manga-ocr, LaMa | App-specific external storage | Downloaded by DownloadManager, SHA-256 verified |
| Update APK | App-specific external storage, `updates/` | Only the release being installed is kept |
| Block lists | `filesDir/blocklist/<source>.txt` | Fetched at runtime, refreshed daily |

`android:allowBackup="false"` plus `data_extraction_rules.xml` (which excludes every domain from cloud backup and from
device-to-device transfer, which Android 12+ would otherwise still do): nothing is backed up or copied to a new
phone, and everything is removed on uninstall.

## Concurrency and memory

- Pipeline stages have their own limits because they contend for different resources: decode 2, detect 2,
  cloud translate 6, on-device translate 1, render 2. A slow engine call does not stop the next capture from
  detecting.
- Each stage has a 50 s watchdog; a timeout fails that image only, with a typed failure.
- Model loading never happens on the main thread. The reader pre-warms the detector, OCR and engine when it opens.
- Patches more than three screens away from the viewport are dropped and re-made on return.
- `PanelglassApp.onTrimMemory` releases idle on-device models and manga-ocr sessions under memory pressure
  (`TRIM_MEMORY_RUNNING_LOW` and above), and logs that it did. When the UI is hidden (`TRIM_MEMORY_UI_HIDDEN`), a
  translator still busy is released 5 s after its last call (`LocalEngines.onHidden`), unless the app comes back
  first (`onVisible`, from the activity callbacks).

## Security and privacy

- **Keys.** Stored only in `SecureKeyStore`; never logged. HTTP bodies are never logged (they can echo a key).
- **Logs.** Timings and failure types only: no keys, page URLs, page text or user IDs.
- **Exported activity.** `MainActivity` accepts a `url` from other apps but loads only `https` (`http` is
  upgraded). The scripted-run extras (`engine`, `src`, `tgt`, `start`) are honoured only in debuggable builds.
- **Debug and release side by side.** Debug builds use the application ID `com.smnexstudio.panelglass.debug` and the
  name "Panelglass Debug": their own data, keys and downloads; release builds keep `com.smnexstudio.panelglass`.
- **In-app updates.** The APK URL must be https under this repository's `releases/download/` (`ReleaseInfo.PREFIX`,
  from the `updateRepo` Gradle property, which the release workflow sets to the repository it runs in); the file is
  checked against the release's `.sha256` before Android's installer sees it, and the installer refuses an APK signed
  with another key. `REQUEST_INSTALL_PACKAGES` and a FileProvider that shares only `updates/` make the
  hand-off possible.
- **User notes are instructions, page text is not.** Engines follow the reader's notes and glossary, but every prompt
  tells the model that page text is data, never instructions. No model has tools: its reply is only drawn as text.
  Every cloud reply is capped by the item count (`LlmContract.outputBudget`) and glossary terms by
  `GlossaryEntry.MAX_CHARS`, so no note or term can make a call write, and bill, an essay.
- **WebView.** No file or content access; no JavaScript interface is exposed to pages; new windows are decided
  natively; blocked hosts never become the main frame.
- **Downloads.** Every model file is pinned to a Hugging Face commit and verified against its SHA-256 before it is
  used; mismatches are deleted. App-specific external storage is private to the app on every supported version
  (Android 12+).

## Testing

| Suite | Command | Covers |
|---|---|---|
| Unit (JVM, Robolectric) | `./gradlew testDebugUnitTest :core:model:test` | Engines against MockWebServer, the LLM contracts, retry policy, block lists, region building, text fitting, patch anchoring and page-change detection, Qwen's backend choice and the release of a model busy when the UI is hidden, download integrity |
| Instrumented | `./gradlew :core:data:connectedDebugAndroidTest` | `SecureKeyStore` round-trip on a device |
| Instrumented | `./gradlew :core:pipeline:connectedDebugAndroidTest` | A rendered page through the whole pipeline |

LiteRT-LM is never loaded in JVM tests: `LocalLlmEngine.runnerFactory` is replaced by a scripted `LlmRunner`.
Every test class, with counts, is listed in [TESTING.md](TESTING.md).
