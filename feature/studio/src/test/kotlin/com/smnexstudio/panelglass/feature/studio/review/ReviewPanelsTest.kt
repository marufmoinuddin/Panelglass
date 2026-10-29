package com.smnexstudio.panelglass.feature.studio.review

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.model.BrushMode
import com.smnexstudio.panelglass.core.model.Bubble
import com.smnexstudio.panelglass.core.model.BubbleStyle
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Pt
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.SfxEdit
import com.smnexstudio.panelglass.core.model.SfxEditMode
import com.smnexstudio.panelglass.core.model.SfxTransform
import com.smnexstudio.panelglass.core.ui.fonts.FontChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.smnexstudio.panelglass.core.ui.R as UiR

/**
 * The review editor's panes on their own: each control of the text, fill, reshape, brush, add-text and sound-effect
 * panels reports the change it names, the colour picker previews and Cancel restores, and the Bubble / Proofread tabs
 * edit and select. Each panel is hosted with its value in state, as the editor holds it. Compose on Robolectric
 * (JUnit 4 via vintage).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewPanelsTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val events = mutableListOf<String>()
    private val choices = FontChoices(favourites = emptyList(), userFontsKey = null, onFavourite = {}, onManage = {})

    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun p(id: Int, n: Int) = context.resources.getQuantityString(id, n, n)

    /** The node's own click: a sheet still animating in under Robolectric would miss an injected touch. */
    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)
    private fun text(t: String) = compose.onNodeWithText(t)
    /** The picker's hex field, showing [code] (its R, G and B fields are text fields too). */
    private fun hexField(code: String) = compose.onNode(hasSetTextAction() and androidx.compose.ui.test.hasText(code))
    /** A control known by its click label (the swatches and the B / I / AA switches have no text of their own). */
    private fun labelled(label: String) =
        compose.onNode(SemanticsMatcher("click label $label") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label })

    private fun bubble(id: Long, kind: RegionKind = RegionKind.ENCLOSED, ignored: Boolean = false) = Bubble(
        id = id, pageId = 1, order = id.toInt(), kind = kind, ignored = ignored,
        polygon = listOf(Pt(0f, 0f), Pt(10f, 0f), Pt(10f, 10f), Pt(0f, 10f)),
    )

    // ---- text style ----------------------------------------------------------------------------------------------

    private var style by mutableStateOf(BubbleStyle())
    private val appliedToAll = mutableListOf<BubbleStyle>()

    private fun textPanel() = compose.setContent {
        Column { TextStylePanel(style, Lang.EN, choices, onStyle = { style = it }, onApplyAll = { appliedToAll += it }) }
    }

    @Test fun boldItalicAndCapitalsAreSwitchesOfTheirOwn() {
        textPanel()
        labelled(s(UiR.string.studio_bold)).tap()
        labelled(s(UiR.string.studio_uppercase)).tap()
        compose.waitForIdle()
        assertEquals(BubbleStyle(bold = true, uppercase = true), style)
        labelled(s(UiR.string.studio_bold)).tap()
        labelled(s(UiR.string.studio_italic)).tap()
        compose.waitForIdle()
        assertEquals(BubbleStyle(italic = true, uppercase = true), style)
    }

    /** Auto (no size) and a fixed size: the pill switches between them, 32 px to start. */
    @Test fun autoSizeTogglesWithAFixedSize() {
        textPanel()
        text(s(UiR.string.studio_size_auto)).tap()
        compose.waitForIdle()
        assertEquals(32f, style.sizePx)
        compose.onNodeWithText("32 px").assertExists()
        text(s(UiR.string.studio_size_auto)).tap()
        compose.waitForIdle()
        assertNull(style.sizePx)
    }

    /** An outline offers its colour row; none takes it away. */
    @Test fun anOutlineHasAWidthAndThenAColour() {
        textPanel()
        compose.onAllNodesWithText(s(UiR.string.studio_outline_color)).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        text(s(UiR.string.studio_outline_thick)).tap()
        compose.waitForIdle()
        assertEquals(4f, style.outlineWidthPx)
        text(s(UiR.string.studio_outline_color)).assertExists()
        text(s(UiR.string.studio_outline_none)).tap()
        compose.waitForIdle()
        assertEquals(0f, style.outlineWidthPx)
    }

    /** "Every bubble" applies the style at once and keeps applying each later change while it is on. */
    @Test fun everyBubbleKeepsApplying() {
        textPanel()
        labelled(s(UiR.string.studio_bold)).tap()
        compose.waitForIdle()
        assertTrue("this bubble only", appliedToAll.isEmpty())
        text(s(UiR.string.studio_apply_every)).tap()
        compose.waitForIdle()
        assertEquals(listOf(BubbleStyle(bold = true)), appliedToAll)
        labelled(s(UiR.string.studio_italic)).tap()
        compose.waitForIdle()
        assertEquals(BubbleStyle(bold = true, italic = true), appliedToAll.last())
    }

    /** Auto text colour is null (ink chosen from what is under the text); a preset sets it. */
    @Test fun textColourAutoAndAPreset() {
        style = BubbleStyle(textColor = 0xFF1F3A93.toInt())
        textPanel()
        labelled(s(UiR.string.studio_color_auto)).tap()
        compose.waitForIdle()
        assertNull(style.textColor)
    }

    /** The full picker previews a typed colour on the page; Cancel puts back the colour it opened with. */
    @Test fun theColourPickerPreviewsAndCancelRestores() {
        style = BubbleStyle(textColor = 0xFF000000.toInt())
        textPanel()
        labelled(s(UiR.string.studio_color_custom)).tap()
        compose.waitForIdle()
        hexField("000000").performTextReplacement("C0392B")
        compose.waitForIdle()
        assertEquals(0xFFC0392B.toInt(), style.textColor)
        text(s(UiR.string.action_cancel)).tap()
        compose.waitForIdle()
        assertEquals(0xFF000000.toInt(), style.textColor)
    }

    @Test fun theColourPickersUseKeepsThePickedColour() {
        textPanel()
        labelled(s(UiR.string.studio_color_custom)).tap()
        compose.waitForIdle()
        hexField("000000").performTextReplacement("27AE60")
        text(s(UiR.string.studio_color_use).uppercase()).tap()
        compose.waitForIdle()
        assertEquals(0xFF27AE60.toInt(), style.textColor)
        assertTrue("the picker closed", compose.onAllNodesWithText(s(UiR.string.studio_color_title)).fetchSemanticsNodes().isEmpty())
    }

    // ---- fill, reshape, brush, add ----------------------------------------------------------------------------------

    @Test fun aFillStartsOpaqueWhiteAndNoFillClearsIt() {
        compose.setContent { Column { FillPanel(style, onStyle = { style = it }, onApplyAll = { appliedToAll += it }) } }
        text(s(UiR.string.studio_fill_mode_colour)).tap()
        compose.waitForIdle()
        assertEquals(0xFFFFFFFF.toInt(), style.fillColor)
        text("100%").assertExists()
        text(s(UiR.string.studio_fill_mode_none)).tap()
        compose.waitForIdle()
        assertEquals(0, style.fillColor)
    }

    @Test fun reshapePresetsAndTheBorder() {
        compose.setContent {
            Column { ReshapePanel(onRectangle = { events += "rect" }, onEllipse = { events += "ellipse" }, style = style, onStyle = { style = it }) }
        }
        text(s(UiR.string.studio_shape_rect)).tap()
        text(s(UiR.string.studio_shape_ellipse)).tap()
        compose.waitForIdle()
        assertEquals(listOf("rect", "ellipse"), events)
        text(s(UiR.string.studio_corners_sharp)).assertExists()
        text(s(UiR.string.studio_outline_thick)).tap()
        compose.waitForIdle()
        assertEquals(7f, style.borderWidthPx)
        text(s(UiR.string.studio_bubble_border_color)).assertExists()
    }

    @Test fun theBrushModeSizeAndUndo() {
        var mode by mutableStateOf(BrushMode.CLEAN)
        compose.setContent {
            Column { BrushPanel(mode, radius = 10f, onMode = { mode = it }, onRadius = {}, strokes = 2, onUndo = { events += "undo" }) }
        }
        text("20 px").assertExists()
        text(p(UiR.plurals.studio_brush_strokes, 2)).assertExists()
        text(s(UiR.string.studio_brush_restore)).tap()
        text(s(UiR.string.studio_undo_last)).tap()
        compose.waitForIdle()
        assertEquals(BrushMode.RESTORE, mode)
        assertEquals(listOf("undo"), events)
    }

    @Test fun noUndoWithoutStrokes() {
        compose.setContent { Column { BrushPanel(BrushMode.CLEAN, radius = 10f, onMode = {}, onRadius = {}, strokes = 0) } }
        assertTrue(compose.onAllNodesWithText(s(UiR.string.studio_undo_last)).fetchSemanticsNodes().isEmpty())
    }

    @Test fun addTextChoosesTextOrASoundEffect() {
        var sfx by mutableStateOf(false)
        compose.setContent { Column { AddTextPanel(sfx, onKind = { sfx = it }) } }
        text(s(UiR.string.studio_add_kind_sfx)).tap()
        compose.waitForIdle()
        assertTrue(sfx)
        text(s(UiR.string.studio_add_kind_text)).tap()
        compose.waitForIdle()
        assertTrue(!sfx)
    }

    // ---- sound effects -----------------------------------------------------------------------------------------------

    private var sfxEdit by mutableStateOf(SfxEdit(transform = SfxTransform(cx = 50f, cy = 50f)))

    private fun sfxPanel(remembered: Boolean = false) = compose.setContent {
        Column {
            SfxPanel(bubble(1, RegionKind.SFX), sfxEdit, Lang.EN, choices, remembered, onEdit = { sfxEdit = it }, onRemember = { events += "remember" })
        }
    }

    @Test fun keepHidesTheReplacementControls() {
        sfxPanel()
        text(s(UiR.string.studio_sfx_presets)).assertExists()
        text(s(UiR.string.studio_sfx_keep)).tap()
        compose.waitForIdle()
        assertEquals(SfxEditMode.KEEP, sfxEdit.mode)
        assertTrue(compose.onAllNodesWithText(s(UiR.string.studio_sfx_presets)).fetchSemanticsNodes().isEmpty())
        text(s(UiR.string.studio_sfx_keep_note)).assertExists()
    }

    @Test fun boldAndADoubleOutlineAreToggles() {
        sfxPanel()
        text(s(UiR.string.studio_bold)).tap()
        text(s(UiR.string.studio_sfx_double_outline)).tap()
        compose.waitForIdle()
        assertTrue(sfxEdit.style.bold)
        assertEquals(android.graphics.Color.WHITE, sfxEdit.style.outerOutlineColor)
        text(s(UiR.string.studio_sfx_double_outline)).tap()
        compose.waitForIdle()
        assertNull(sfxEdit.style.outerOutlineColor)
    }

    @Test fun aPresetIsRecordedOnTheEffect() {
        sfxPanel()
        text(s(UiR.string.studio_sfx_preset_impact)).tap()
        compose.waitForIdle()
        assertEquals("IMPACT", sfxEdit.preset)
    }

    @Test fun rememberIsOfferedUntilTheEffectIsRemembered() {
        sfxPanel()
        text(s(UiR.string.studio_sfx_remember)).tap()
        compose.waitForIdle()
        assertEquals(listOf("remember"), events)
    }

    @Test fun aRememberedEffectSaysSo() {
        sfxPanel(remembered = true)
        text(s(UiR.string.studio_sfx_remembered)).assertExists()
        assertTrue(compose.onAllNodesWithText(s(UiR.string.studio_sfx_remember)).fetchSemanticsNodes().isEmpty())
    }

    // ---- bubble and proofread tabs, header --------------------------------------------------------------------------------

    @Test fun withoutABubbleTheTabSaysWhatToDo() {
        compose.setContent { BubbleTab(null, count = 0, texts = null, src = Lang.JA, tgt = Lang.EN, retranslating = false, onSource = {}, onTranslation = {}, onRetranslate = {}) }
        text(s(UiR.string.studio_no_bubbles)).assertExists()
    }

    @Test fun theBubbleTabEditsBothTextsAndAsksBeforeDeleting() {
        var texts by mutableStateOf(BubbleTexts("", ""))
        compose.setContent {
            BubbleTab(
                bubble(1), count = 1, texts = texts, src = Lang.JA, tgt = Lang.EN, retranslating = false,
                onSource = { texts = texts.copy(source = it) }, onTranslation = { texts = texts.copy(translation = it) },
                onRetranslate = { events += "retranslate" }, onToggleIgnored = { events += "ignore" }, onDelete = { events += "delete" },
            )
        }
        // The original's field first, then the translation's.
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("invented source")
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("invented line")
        compose.waitForIdle()
        assertEquals(BubbleTexts("invented source", "invented line"), texts)
        text(s(UiR.string.studio_retranslate)).tap()
        text(s(UiR.string.studio_not_text)).tap()
        text(s(UiR.string.studio_delete_bubble)).tap()
        compose.waitForIdle()
        assertEquals("delete waits for the confirmation", listOf("retranslate", "ignore"), events)
        text(s(UiR.string.studio_delete_bubble_confirm)).assertExists()
        text(s(UiR.string.action_delete).uppercase()).tap()
        compose.waitForIdle()
        assertEquals("delete", events.last())
    }

    @Test fun aDismissedBubbleOffersRestore() {
        compose.setContent {
            BubbleTab(bubble(1, ignored = true), 1, BubbleTexts("a", "b"), Lang.JA, Lang.EN, false, {}, {}, {}, onToggleIgnored = { events += "restore" })
        }
        text(s(UiR.string.studio_dismissed)).assertExists()
        text(s(UiR.string.studio_restore_bubble)).tap()
        compose.waitForIdle()
        assertEquals(listOf("restore"), events)
    }

    /** Dismissed bubbles have no number; effects can be listed alone; a tap selects. */
    @Test fun proofreadNumbersFiltersAndSelects() {
        val all = listOf(bubble(1), bubble(2, ignored = true), bubble(3, RegionKind.SFX))
        compose.setContent {
            ProofreadTab(all, texts = { BubbleTexts("src ${it.id}", "line ${it.id}") }, selectedId = null, onSelect = { events += "select $it" })
        }
        text("line 1").assertExists()
        text("–").assertExists()
        text(s(UiR.string.studio_sfx_only)).tap()
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("line 1").fetchSemanticsNodes().isEmpty())
        text("line 3").tap()
        assertEquals(listOf("select 3"), events)
    }

    @Test fun theHeaderStepsThroughBubblesAndSwitchesTabs() {
        compose.setContent {
            EditorHeader(EditorTab.BUBBLE, onTab = { events += "tab $it" }, index = 0, count = 3, onPrev = { events += "prev" }, onNext = { events += "next" })
        }
        text(s(UiR.string.studio_bubble_of, 1, 3)).assertExists()
        compose.onNodeWithContentDescription(s(UiR.string.studio_previous_bubble)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(s(UiR.string.studio_next_bubble)).tap()
        compose.onNodeWithContentDescription(s(UiR.string.studio_tab_proofread)).tap()
        assertEquals(listOf("next", "tab PROOFREAD"), events)
    }
}
