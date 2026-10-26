package com.smnexstudio.panelglass.core.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every feature must be reachable from the app.
 *
 * A feature that ships only its engine — the plan, the view model, the strings — compiles, passes every unit test
 * and even lands in the APK, and is still unreachable: no button calls it. That is how the whole-page scan was
 * lost once: the engine and the view model were merged, the screen wiring was not, and nothing complained.
 *
 * This test is the guard. It reads the source and asks two questions of every feature:
 *
 *  1. **Is it wired?** The feature's entry point (a public function the view model exposes) has a caller in the UI
 *     layer — a screen, a view model of another feature, or a shared UI component.
 *  2. **Is it shown?** The strings the feature puts on screen are referenced by code, not only declared.
 *
 * A feature is declared in [FEATURES]. When one is added, its entry point and its strings go here too, and the
 * test fails until the wiring is in place.
 */
class FeatureReachabilityTest {
    private val repo: File = repoRoot()

    /** The features of the app, and the entry points that start them. */
    private data class Feature(
        val name: String,
        /** The module the feature lives in. */
        val module: String,
        /** The view model that exposes it. */
        val viewModel: String,
        /** The public functions that start or drive the feature. */
        val entryPoints: List<String>,
        /** The strings the feature puts on screen. */
        val strings: List<String>,
    )

    private val features = listOf(
        Feature(
            name = "Custom AI engine",
            module = "core/engine",
            viewModel = "core/engine/src/main/kotlin/com/smnexstudio/panelglass/core/engine/llm/CustomOpenAiEngine.kt",
            entryPoints = emptyList(), // the engine is reached through the registry, not a view model
            strings = emptyList(),
        ),
        Feature(
            name = "Offline model library",
            module = "feature/settings",
            viewModel = "feature/settings/src/main/kotlin/com/smnexstudio/panelglass/feature/settings/SettingsViewModel.kt",
            entryPoints = listOf("importFile", "importInfo", "addFromHub", "deleteUserModel", "useUserModel"),
            strings = listOf("models_import", "models_browse"),
        ),
        Feature(
            name = "Compute backend setting",
            module = "feature/settings",
            viewModel = "feature/settings/src/main/kotlin/com/smnexstudio/panelglass/feature/settings/SettingsViewModel.kt",
            entryPoints = listOf("setComputeBackend"),
            strings = listOf("backend_auto", "backend_gpu", "backend_cpu"),
        ),
        Feature(
            name = "Whole-page scan",
            module = "feature/browser",
            viewModel = "feature/browser/src/main/kotlin/com/smnexstudio/panelglass/feature/browser/ReaderViewModel.kt",
            entryPoints = listOf("startFullPage", "cancelScan", "onUserTouch"),
            strings = listOf("reader_translate_whole_page", "reader_scan_progress", "reader_scan_cancel"),
        ),
        Feature(
            name = "Translation prompt",
            module = "feature/settings",
            viewModel = "feature/settings/src/main/kotlin/com/smnexstudio/panelglass/feature/settings/SettingsViewModel.kt",
            entryPoints = listOf("setTranslationPrompt"),
            strings = listOf("settings_prompt", "settings_prompt_empty"),
        ),
    )

    /** The source of the UI layer: what a user can actually reach. */
    private fun uiSources(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (dir in listOf("feature", "core/ui", "app")) {
            val base = File(repo, dir)
            if (!base.isDirectory) continue
            base.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".kt") }
                .forEach { out[it.path] = it.readText() }
        }
        return out
    }

    @Test
    fun everyFeatureEntryPointHasAUserReachableCaller() {
        val ui = uiSources()
        val dead = mutableListOf<String>()

        for (f in features) {
            val vmFile = File(repo, f.viewModel)
            if (!vmFile.exists()) {
                dead += "${f.name}: its view model is gone (${f.viewModel})"
                continue
            }
            val vm = vmFile.readText()
            for (entry in f.entryPoints) {
                if (!Regex("fun\\s+" + Regex.escape(entry) + "\\s*\\(").containsMatchIn(vm)) {
                    dead += "${f.name}: $entry is no longer a function of the view model"
                    continue
                }
                // The caller must live in the UI layer and be a different file: a view model calling itself is not a
                // user reaching the feature. The call may end in a trailing lambda (`vm.startFullPage { ... }`).
                val callers = ui.filter { (path, src) ->
                    path != f.viewModel && Regex("\\." + Regex.escape(entry) + "\\s*[({]").containsMatchIn(src)
                }
                if (callers.isEmpty()) {
                    dead += "${f.name}: $entry has no caller in the UI layer — the feature cannot be reached"
                }
            }
        }
        assertEquals(emptyList<String>(), dead, "a feature is not reachable from the app")
    }

    @Test
    fun everyFeatureStringIsShownByCode() {
        val all = mutableMapOf<String, String>()
        for (dir in listOf("feature", "core", "app")) {
            val base = File(repo, dir)
            if (!base.isDirectory) continue
            base.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".kt") && !it.path.contains("/build/") }
                .forEach { all[it.path] = it.readText() }
        }
        val kotlin = all.values.joinToString("\n")

        val unused = mutableListOf<String>()
        for (f in features) {
            for (s in f.strings) {
                // The string is used by name in the code (R.string.x or UiR.string.x).
                if (!Regex("\\b(?:R|UiR)\\.string\\.$s\\b").containsMatchIn(kotlin)) {
                    unused += "${f.name}: the string $s is declared but never shown"
                }
            }
        }
        assertEquals(emptyList<String>(), unused, "a feature's string is declared but never used")
    }

    @Test
    fun theWholePageScanHasItsMenuItem() {
        // The exact thing that was lost: the engine and the view model were merged, the menu item was not.
        val screen = File(repo, "feature/browser/src/main/kotlin/com/smnexstudio/panelglass/feature/browser/ReaderScreen.kt")
        assertTrue(screen.exists(), "the reader screen is gone")
        val src = screen.readText()
        assertTrue(
            src.contains("reader_translate_whole_page"),
            "the reader's menu has no \"Translate whole page\" item: the scan cannot be started",
        )
        assertTrue(src.contains("vm.startFullPage"), "nothing starts the whole-page scan")
    }

    private companion object {
        fun repoRoot(): File {
            var dir: File? = File("").absoluteFile
            repeat(4) {
                if (dir != null && File(dir, "settings.gradle.kts").exists()) return dir
                dir = dir?.parentFile
            }
            return File("").absoluteFile
        }
    }
}
