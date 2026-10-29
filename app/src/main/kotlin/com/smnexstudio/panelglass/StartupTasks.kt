package com.smnexstudio.panelglass

import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.model.EngineId

/**
 * What [PanelglassApp] settles at every start, apart from the Application so it is unit-tested:
 *
 * - Someone who installed before Google Translate became the default and never picked an engine was on Qwen: when
 *   the Qwen model is on the phone, that choice is written down so the new default does not switch them.
 * - Google Translate's Japanese, Korean and Chinese packs are fetched until they are all there once; after that a pack
 *   the user deletes is not fetched again. Offline, the fetch fails and the next start tries again.
 */
internal class StartupTasks(
    private val settings: SettingsRepository,
    private val qwenOnPhone: () -> Boolean,
    private val fetchAutomaticPacks: suspend () -> Boolean,
) {
    suspend fun run() {
        if (!settings.hasEngineChoice() && qwenOnPhone()) settings.setEngine(EngineId.QWEN15_LOCAL)
        if (!settings.automaticPacksDone() && fetchAutomaticPacks()) settings.setAutomaticPacksDone()
    }
}
