package com.smnexstudio.panelglass.feature.settings

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.ui.EnginePickerSheet
import com.smnexstudio.panelglass.core.ui.KeyedEngineState
import com.smnexstudio.panelglass.core.ui.uiName
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.smnexstudio.panelglass.core.ui.R as UiR

/**
 * The engine picker's two targets per row (CLAUDE.md › Picker): the disc selects (a keyed engine without a key asks for
 * the key instead), the name of a keyed engine opens its sheet without selecting. Planned and hidden engines are never
 * listed. Compose on Robolectric (JUnit 4 via vintage).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnginePickerTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val events = mutableListOf<String>()

    private fun picker(hasGeminiKey: Boolean, hidden: Set<EngineId> = emptySet()) = compose.setContent {
        EnginePickerSheet(
            selected = EngineId.GOOGLE,
            keyed = { KeyedEngineState(hasKey = it == EngineId.GEMINI && hasGeminiKey, model = "gemini-flash-latest") },
            onSelect = { events += "select $it" },
            onNeedsKey = { events += "needsKey $it" },
            onDismiss = { events += "dismiss" },
            onConfigure = { events += "configure $it" },
            hidden = hidden,
        )
    }

    private fun disc(id: EngineId) = compose.onNodeWithContentDescription(app.getString(UiR.string.cd_select_engine, id.uiName(app)), useUnmergedTree = true)

    /** The node's own click action: a bottom sheet still animating in under Robolectric would miss an injected touch. */
    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    @Test fun aKeyedEngineWithoutAKeyAsksForTheKeyInsteadOfSelecting() {
        picker(hasGeminiKey = false)
        disc(EngineId.GEMINI).tap()
        assertEquals(listOf("needsKey GEMINI"), events)
    }

    @Test fun aKeyedEngineWithAKeyIsSelectedAndTheSheetCloses() {
        picker(hasGeminiKey = true)
        disc(EngineId.GEMINI).tap()
        assertEquals(listOf("select GEMINI", "dismiss"), events)
    }

    @Test fun theNameOfAKeyedEngineOpensItsSheetWithoutSelecting() {
        picker(hasGeminiKey = true)
        compose.onNodeWithText(EngineId.GEMINI.uiName(app)).tap()
        assertEquals(listOf("configure GEMINI"), events)
    }

    @Test fun anOnDeviceEngineIsSelectedAtOnce() {
        picker(hasGeminiKey = false)
        disc(EngineId.QWEN15_LOCAL).tap()
        assertEquals(listOf("select QWEN15_LOCAL", "dismiss"), events)
    }

    @Test fun plannedAndHiddenEnginesAreNotListed() {
        picker(hasGeminiKey = false, hidden = setOf(EngineId.GEMMA4_LOCAL))
        for (e in EngineId.entries.filter { !it.offered } + EngineId.GEMMA4_LOCAL) {
            compose.onAllNodesWithText(e.uiName(app), useUnmergedTree = true).assertCountEquals(0)
        }
        compose.onNodeWithText(EngineId.GOOGLE.uiName(app), useUnmergedTree = true).assertExists()
    }
}
