package com.smnexstudio.panelglass.feature.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * Every control a screen reader can reach that it could only announce as "button" or "switch": clickable or toggleable
 * nodes (merged, as TalkBack sees them) with no text, no content description, no editable text and no click label.
 * Returned as "role at bounds", for a readable failure.
 */
internal fun ComposeTestRule.unlabelledControls(): List<String> {
    val control = hasClickAction() or SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)
    return onAllNodes(control).fetchSemanticsNodes().filter { n ->
        val c = n.config
        c.getOrNull(SemanticsProperties.Text).orEmpty().all { it.isBlank() } &&
            c.getOrNull(SemanticsProperties.ContentDescription).orEmpty().all { it.isBlank() } &&
            c.getOrNull(SemanticsProperties.EditableText) == null &&
            c.getOrNull(SemanticsActions.OnClick)?.label.isNullOrBlank()
    }.map { n -> (n.config.getOrNull(SemanticsProperties.Role)?.toString() ?: "control") + " at " + n.boundsInRoot }
}
