package com.smnexstudio.panelglass.core.engine

import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.engine.local.NpuGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The guard against an NPU that takes the app down with it: an attempt that does not finish leaves a note, and the
 * next attempt on the same model is blocked.
 */
@RunWith(RobolectricTestRunner::class)
class NpuGuardTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val guard = NpuGuard(context)

    @Test fun aFreshGuardBlocksNothing() {
        assertFalse(guard.isBlocked("model.litertlm"))
    }

    @Test fun anAttemptThatFinishedLeavesNoTrace() {
        guard.markAttempt("model.litertlm")
        guard.clearAttempt()
        // The next attempt finds no note: the attempt before it finished.
        guard.checkPreviousAttempt("model.litertlm")
        assertFalse(guard.isBlocked("model.litertlm"))
    }

    @Test fun anAttemptThatCrashedBlocksThatModel() {
        // The attempt started, and the process died before it could clear the note.
        guard.markAttempt("model.litertlm")
        // The next start finds the note still there.
        guard.checkPreviousAttempt("model.litertlm")
        assertTrue(guard.isBlocked("model.litertlm"))
    }

    @Test fun onlyTheModelThatCrashedIsBlocked() {
        guard.markAttempt("crashed.litertlm")
        guard.checkPreviousAttempt("crashed.litertlm")
        assertTrue(guard.isBlocked("crashed.litertlm"))
        assertFalse(guard.isBlocked("other.litertlm"))
    }

    @Test fun pickingABackendAgainClearsTheBlock() {
        guard.markAttempt("model.litertlm")
        guard.checkPreviousAttempt("model.litertlm")
        assertTrue(guard.isBlocked("model.litertlm"))
        // The user picks a backend on purpose: the model may be tried once more.
        guard.clearBlocked()
        assertFalse(guard.isBlocked("model.litertlm"))
    }
}
