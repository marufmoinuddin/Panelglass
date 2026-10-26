# Changelog

User-visible changes to Panelglass. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
versions follow [Semantic Versioning](https://semver.org/). A pull request adds its entry under **Unreleased**; at
release time a maintainer renames that heading to the version and date (see CONTRIBUTING › Releases). The release
workflow publishes the version's section as the GitHub Release notes.

## [Unreleased]

### Fixed

- **Translations no longer vanish while you scroll.** The reader kept three screens of finished translations in
  memory and threw the rest away, so scrolling far down and back meant detecting, reading and paying for every
  bubble a second time. A finished translation is now also kept on disk, as the rendered bubble, its place on the
  image and a fingerprint of the settings it was made with. Coming back to it costs no detector, no OCR and no AI
  call at all. Settings › **Translation memory** says how many screens are kept in memory, how much memory they may
  use, how long the saved ones live (off, this chapter, or seven days) and how much disk they may take; a **Clear
  saved translations** button empties the store.

### Added

- **Dialogue context.** Settings › Translation gains a three-way choice: *Off* (as before), *Standard* (six lines) or
  *Deep* (twelve). The reader remembers every bubble it has translated and sends the lines that come just before the
  new screen, so a pronoun has an antecedent and a name is the same name. The cloud engines and your own server are
  also asked who says each line, and the earlier lines are shown to the model with the speaker's label. The
  on-device models get at most four lines: their cache is small.
- **A character sheet per website.** ⋮ › *Characters…* in the reader opens the cast of the website being read — one
  line per character — and it goes into the prompt as data, so the model keeps names, genders and pronouns
  consistent. It is never shared, and it is not sent anywhere but the engine that is translating.
- **A warning when the model is too small to follow the translation format.** A model of about a billion parameters or
  less usually cannot be told to answer with a JSON array; the sheet says so under the model field, without stopping
  you from saving it.
- **Custom AI can use a model server on your own network.** A server at home — `http://192.168.0.15:8910/v1` — is
  now reachable over plain http, and the API key for it is **optional**: leave the key empty and the request goes
  out with no `Authorization` header at all. The address is read as you type it and the sheet says at once whether it
  can be used: that plain http is only for your own network, that a username in the address is refused, or that the
  address is not one. **Refresh** asks the server for its own model list, and the engine picker then shows the
  address and the model. Plain http to a host that is *not* on your network is still refused — use https, or a VPN
  such as Tailscale.

### Fixed

- **The reply of a local server is read more patiently.** A server at home may answer with a bare object instead of an
  array, with the array inside a code fence, wrapped in a `translations` key, or with the translation under
  `english` or `translation`; all of those are read now. A reply that cannot be read is a plain "malformed" failure
  instead of a crash, and a reply whose text holds brackets is not cut apart by them.
- **The retry no longer depends on the conversation.** Some local servers rewrite a multi-turn history — the original
  input dropped, the order reversed — so a retry that asked "your previous reply was wrong" could never work. The
  Custom AI engine now sends the input again with the correction, in one message, and the system prompt shows what
  a reply looks like when there is a single item.
- **Plain http is refused to every address that is not on the local network, including IPv6 addresses that only look
  local; the update check and downloads follow the same rule.** An IPv6 address such as `2606:4700::ffff:c0a8:1`
  carries `192.168.0.1` in its last four bytes and was judged to be on the local network, so plain http to it was
  allowed — through a redirect too. It is refused now, and so are multicast addresses and the deprecated site-local
  range. The update check had a client of its own without the guard, and model downloads are queued through
  `DownloadManager`, which follows redirects on its own and no interceptor of ours can see: only `https` is queued
  now.
- **Importing a model file could not work.** The progress row formatted a string with fewer arguments than it
  needed, which took the Settings screen down as soon as an import started. The row has its own string now, and the
  screen checks every string it shows against the arguments that string declares.
- **Importing a model file now works from the file manager.** The extension is matched without regard to case
  (`MODEL.LITERTLM` is accepted), the file is copied under a generated name so its own name is never used as a path,
  a file that is not a model or is too small is refused with a message instead of being added and found broken later,
  the same file twice does not make two models, and a failed import says why instead of failing silently.

### Added

- **Glossary & pronouns** in the reader (⋮): character names and terms per series, each character with a pronoun
  (he, she, they), plus notes for the series. Gemini and the on-device models use them; Google Translate uses the
  names and terms.
- **Translation notes** in Settings: instructions for the AI engines on every series (tone, honorifics, how to treat
  missing subjects).
- **Sound effects** setting: Keep or Replace (the default) the original effects in the reader.
- **A second text detector** (PP-OCRv6, bundled, 1.8 MB) finds sound effects and other text the comic detector missed; with Replace, effects nothing can read are still erased.
- **In-app updates**: a newer release is offered once a day (Update or Later), downloaded
- More Japanese and Korean sound effects in the built-in dictionary; hiragana and katakana spellings, and an extra
- **Run models on** setting: choose Automatic, CPU, GPU or (experimental, Snapdragon only) NPU for every on-device
  model — Qwen, Gemma 4 and your own models alike — and the app shows which backend actually ran.
- **Offline models**: bring your own LiteRT-LM model. Settings › Models › **Browse models** searches the Hugging Face
  catalogue (or type `owner/name` to list a repository directly) and downloads any `.litertlm` file; **Import from
  file** adds one already on the phone. Keep several, delete them again, and tap **Use** to pick the one the new
  **Offline model** engine runs. Only the LiteRT-LM format works — the one the bundled runtime loads. A gated model
  has to be downloaded in a browser and imported.
- **Translation prompt** in Settings: your own instructions for every translation (a house style, what to avoid,
  how to render a term). The AI engines follow them; machine translation has no prompt and ignores them. The notes
  are about one series, the prompt is about every translation.
- **Custom AI**, a translation engine for your own OpenAI-compatible server. Name its address and the model in
  Settings and paste your key: the app sends a plain text Chat Completions request to `<your address>/chat/completions`
  and reads the text on the device, so no image ever leaves the phone. The address must be HTTPS; a pasted
  `…/chat/completions` address is accepted and no `/v1` is added for you.

### Changed

- The reader switches to the page's own language (Japanese, Korean or Chinese) when it differs from Settings, unless
  the site has its own.
- Text that reads as nothing is read again in high contrast, which finds narration on dark art and outlined lettering.
- Editing a glossary or notes translates pages again instead of showing cached ones.
- Debug builds install beside the release app as "Panelglass Debug", help to have release version and debug version(developement) side by side.

## [0.2.0] - 2026-10-02

### Added

- **Studio**, a new tab that translates chapters you already have into image files:
  - **Import** images, a PDF or a `.cbz`. ComicInfo.xml fills in the details, and a CBZ with a folder per chapter
    becomes several chapters. Manga get covers, details and a searchable library.
  - **Translate** a whole chapter with the engine chosen in Settings; stop and **Continue** at any time.
  - **Review** bubble by bubble: correct the original or the translation, re-translate, add missed text, mark false
    detections *Not text* or delete them, and mark pages reviewed. Zoom up to 8×; undo and redo every step.
  - **Clean and restyle**: the original text is removed and the translation lettered inside each balloon's traced
    shape. Set font, size, colour, outline, fill, border and rounded corners per bubble or page; reshape bubbles and
    brush out leftovers. Optional **LaMa** cleanup (208 MB) rebuilds artwork under text.
  - **Sound effects**: keep or replace, with presets, and move, turn, stretch or bend them.
  - **Fonts**: 21 bundled fonts with a default for every target language, plus your own `.ttf` / `.otf` / `.ttc`.
  - **Export** a chapter or a whole manga as images, CBZ or ZIP (with ComicInfo.xml) to Download/Panelglass or a
    folder you pick.
- **Reader fonts**: Settings › Fonts offers every Studio font and your own. **Auto**, the new default, picks a comic
  font made for the target language.
- **Tablets and Chromebooks**: a navigation rail and side-by-side layouts on wide windows, and keyboard shortcuts in
  the editor.
- **Settings › Storage** lists each Studio manga's size and deletes it.

### Changed

- **Google Translate is the default engine** on a new install. If you use Qwen and never picked an engine, you stay
  on Qwen.
- **Language packs download only when needed**: Japanese, Korean and Chinese at first start; any other when you tap
  **Download** or pick it in Settings › Models.
- Panelglass now needs **Android 12** or later.
- **https only**: `http://` addresses and links open as `https://`; a site that only serves http no longer loads.
- Links opened from another app are no longer translated automatically; tap **Start**.
- Translations stay on the page while you zoom.
- Moving to a new phone no longer copies Panelglass's data.

### Fixed

- Changing the reader font now redraws pages translated earlier.
- A very large block list can no longer exhaust memory, and a damaged cache file is discarded safely.
- A web page can no longer impersonate Panelglass's in-page helper, and an oversized reply from a page is ignored.
- Pop-up windows are checked without running their scripts or reading local files.

## [0.1.0] - 2026-09-27

The first release. Panelglass is a manga translator for Android: open a raw manga, manhwa, manhua or webtoon site,
press **Start**, and every speech balloon, caption and sound effect is erased and redrawn in your language, right
where it was. It needs Android 8.0 or later.

### Added

- **Screen translation in the reader.**
  - Press **Start** and the screen is translated. After each scroll, the next screen is translated once the page
    settles.
  - Translations stay pinned to the artwork while you scroll or the site shifts its layout.
  - Scrolling and paged readers both work: a tap or swipe page turn is noticed, pages still downloading are picked
    up when they arrive, and rotating the phone translates the new layout.
  - Balloons cut off at the screen edge wait until they are fully on screen.
  - Only comic art is translated, never the site's toolbars floating above it.
  - **⋮ → Re-translate** reads the current screen again from scratch.
- **In-place lettering.**
  - The original text is removed and the translation is fitted into the same balloon or caption box, sized to stay
    readable.
  - Vertical Japanese is supported.
  - Three lettering fonts are included.
- **Four translation engines, cloud or fully offline:**
  - **Gemini**, with your own API key: it reads each balloon itself for the best quality and speed, and works with
    any Gemini or Gemma model your key can use.
  - **Google Translate**: on-device (ML Kit), free, with ~30 MB offline language packs.
  - **Qwen 2.5 1.5B**: an on-device model, a one-time 1.6 GB download. It runs on the GPU, or on the CPU on phones
    under 6 GB of RAM; **Settings → Models → Qwen runs on** changes it.
  - **Gemma 4 E2B**: a higher-quality on-device model, a 2.6 GB download for phones with 8 GB of RAM.
- **Built for comics.**
  - A bundled comic text and bubble detector finds the lettering.
  - Japanese is read by the optional manga-ocr model (140 MB download) or by ML Kit.
  - Model downloads keep going in the background and resume after interruptions. Every file is checked against a
    pinned SHA-256.
- **Languages.**
  - Translate from Japanese, Korean, Chinese (Simplified and Traditional), English, Spanish, French, German,
    Italian, Portuguese, Russian, Indonesian or Vietnamese.
  - Translate into any of those, or into Thai, Arabic or Hindi.
- **Your sites.**
  - No sites ship with the app: add the readers you use and pin favourites.
  - Set languages, auto-translate and ad blocking per site.
  - Recent chapters are kept in History.
- **Ad and pop-up blocking.**
  - Public block lists (AdGuard DNS, HaGeZi pop-up ads, StevenBlack as a fallback) are fetched and refreshed daily.
  - Pop-ups and popunders are caught, and interstitials and tap-hijacking overlays are cleaned up.
- **Translate page** (**⋮ → Translate page**): translates the site's own text, such as titles, chapter lists and
  comments, on the device. **Show original** puts the page back.
- **Try a translation** box in Settings, to check an engine before reading.
- **Themes and app language.**
  - Four themes: Default, Panel Pop, Soft Bloom, and Paper & Ink.
  - The interface is available in 16 languages (Arabic right to left), switched in **Settings → App language**.
- **Privacy.**
  - API keys are encrypted with the Android Keystore and never logged.
  - The offline engines keep everything on the device, and there is no Panelglass server.
  - Nothing is backed up, and uninstalling removes everything.
