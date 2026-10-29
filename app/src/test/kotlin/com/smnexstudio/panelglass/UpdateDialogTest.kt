package com.smnexstudio.panelglass

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.data.update.Release
import com.smnexstudio.panelglass.core.data.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import com.smnexstudio.panelglass.core.ui.R as UiR

/**
 * The app-wide update dialog in each state of the update flow: what it says and what each button asks for. No false
 * offer: only Available offers Update, a failed check offers no retry, a download cannot be put off with Later.
 * Compose on Robolectric (JUnit 4 via vintage); a plain Application, not the Hilt one, so nothing starts up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateDialogTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val release = Release("0.3.0", "panelglass-0.3.0.apk", "https://github.com/x/y/releases/download/v0.3.0/panelglass-0.3.0.apk", 1_000_000, "https://github.com/x/y/releases/download/v0.3.0/panelglass-0.3.0.apk.sha256")
    private val actions = mutableListOf<String>()
    private var state by mutableStateOf<UpdateState>(UpdateState.Idle)

    private fun show(s: UpdateState) {
        state = s
        compose.waitForIdle()
    }

    private fun setUp() = compose.setContent {
        UpdateHost(state, onUpdate = { actions += "update" }, onDismiss = { actions += "dismiss" }, onCancel = { actions += "cancel" })
    }

    private fun text(id: Int, vararg args: Any) = app.getString(id, *args)
    /** The button's own click action: a dialog still animating in under Robolectric would miss an injected touch. */
    private fun tap(label: String) = compose.onNodeWithText(label, ignoreCase = true).performSemanticsAction(SemanticsActions.OnClick)
    private fun absent(label: String) = compose.onAllNodesWithText(label, ignoreCase = true, useUnmergedTree = true).assertCountEquals(0)

    @Test fun idleShowsNothing() {
        setUp()
        absent(text(UiR.string.update_available_title))
        absent(text(UiR.string.update_checking))
    }

    @Test fun anAvailableReleaseOffersUpdateOrLater() {
        setUp()
        show(UpdateState.Available(release, "0.2.0"))
        compose.onNodeWithText(text(UiR.string.update_available_message, "0.3.0", "0.2.0")).assertExists()
        tap(text(UiR.string.update_action))
        tap(text(UiR.string.update_later))
        assertEquals(listOf("update", "dismiss"), actions)
    }

    @Test fun aManualCheckShowsItsProgressAndCanBeCancelled() {
        setUp()
        show(UpdateState.Checking)
        compose.onNodeWithText(text(UiR.string.update_checking)).assertExists()
        tap(text(UiR.string.action_cancel))
        assertEquals(listOf("cancel"), actions)
    }

    @Test fun upToDateSaysSoAndOffersNoUpdate() {
        setUp()
        show(UpdateState.UpToDate("0.3.0"))
        compose.onNodeWithText(text(UiR.string.update_up_to_date, "0.3.0")).assertExists()
        absent(text(UiR.string.update_action))
        tap(text(UiR.string.action_close))
        assertEquals(listOf("dismiss"), actions)
    }

    /** A failed check (GitHub unreachable) is a message with Close only; nothing is offered. */
    @Test fun aFailedCheckOffersNoRetryOrUpdate() {
        setUp()
        show(UpdateState.Failed(null))
        compose.onNodeWithText(text(UiR.string.update_failed_check)).assertExists()
        absent(text(UiR.string.action_retry))
        absent(text(UiR.string.update_action))
    }

    @Test fun aFailedDownloadOffersRetry() {
        setUp()
        show(UpdateState.Failed(release))
        compose.onNodeWithText(text(UiR.string.update_failed_download)).assertExists()
        tap(text(UiR.string.action_retry))
        assertEquals(listOf("update"), actions)
    }

    @Test fun aDownloadShowsProgressAndOnlyCancel() {
        setUp()
        show(UpdateState.Downloading(release, 250_000, 1_000_000))
        compose.onNodeWithText(text(UiR.string.update_downloading)).assertExists()
        absent(text(UiR.string.update_later))
        tap(text(UiR.string.action_cancel))
        assertEquals(listOf("cancel"), actions)
    }

    @Test fun aVerifiedDownloadOffersInstallOrLater() {
        setUp()
        show(UpdateState.Ready(release, File(app.filesDir, "panelglass-0.3.0.apk")))
        compose.onNodeWithText(text(UiR.string.update_ready_message, "0.3.0")).assertExists()
        compose.onNodeWithText(text(UiR.string.update_install), ignoreCase = true, useUnmergedTree = true).assertExists()
        tap(text(UiR.string.update_later))
        assertEquals(listOf("dismiss"), actions)
    }
}
