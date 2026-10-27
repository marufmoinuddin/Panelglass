# Panelglass documentation

Start with the question you have; each document answers one kind of question.

## I want to use the app

| Document | Read it for |
|---|---|
| [README](../README.md) | What Panelglass does, install and update, first setup, engines, privacy, FAQ |
| [Translation quality](TRANSLATION_QUALITY.md) | Getting better translations: glossary and pronouns, translation notes, page language, sound effects, known limits |
| [Changelog](../CHANGELOG.md) | What changed in each release |

## I want to contribute

| Document | Read it for |
|---|---|
| [CONTRIBUTING](../CONTRIBUTING.md) | Branches and pull requests, building, the checklist, rules that are easy to break, releases |
| [Architecture](ARCHITECTURE.md) | The modules and how they depend on each other, runtime components, engines, storage, security |
| [Translation flow](FLOW.md) | One screen's journey: capture → pipeline → translation → erase and letter → patches on the page; failures; diagnostics |
| [Detection and OCR](DETECTION_AND_OCR.md) | How text is found (two detectors) and read (manga-ocr, ML Kit), and how lines become regions |
| [Testing](TESTING.md) | Every test class and what it covers, instrumented tests, the checks that need a phone |
| [Memory usage](MEMORY_USAGE.md) | RAM per engine on a real phone, and how to measure it again |

## Where things are in the code

```
app/                  MainActivity, navigation, in-app update dialog
core/model/           Pure Kotlin types: languages, engines, failures, regions, settings, glossary, notes
core/data/            Room, DataStore, encrypted keys, patch cache, verified downloads, in-app updates
core/engine/          Engine registry and retries; Gemini, ML Kit translation, on-device LLMs, SFX dictionary
core/ocr/             Comic detector, PP-OCRv6 second pass, manga-ocr / ML Kit recognizers, region building
core/render/          Erasing, text fitting, patches; the Studio's cleaning, lettering, sound effects and fonts
core/pipeline/        TranslationPipeline: detect → read → translate → render, watchdogs, patch cache
core/ui/              Shared Compose components, themes, glossary sheet, every UI string (16 languages)
feature/browser/      The reader: WebView, capture, patch overlay, page language, ad and pop-up blocking
feature/library/      Saved sites and history
feature/settings/     Languages, engines, sound effects, notes, models, fonts, themes, storage, updates
feature/studio/       Studio: library, import, translate, review editor, fonts, export
```

## Words used in these documents

| Term | Meaning |
|---|---|
| **Capture** | One snapshot of the reader's visible screen, sent through the pipeline |
| **Region** | One unit of text to translate and redraw: usually one speech balloon, caption or sound effect |
| **Patch** | The small WEBP image drawn over the page in place of a region's original lettering |
| **ROI** (region of interest) | Which parts of a capture may be read: the page's images, minus controls drawn over them |
| **Second pass** | The PP-OCRv6 detector that looks for text the comic detector missed (mostly sound effects) |
| **Series** | The scope of a glossary and notes: a saved site, or the host and first path segment of the page |
| **Debug build** | `com.smnexstudio.panelglass.debug`, "Panelglass Debug": installs beside the release app, with its own data |
