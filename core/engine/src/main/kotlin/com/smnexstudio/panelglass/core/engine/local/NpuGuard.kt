package com.smnexstudio.panelglass.core.engine.local

import android.content.Context

/**
 * A guard against the NPU taking the whole app down.
 *
 * An NPU that crashes does not throw a Kotlin exception: it takes the process with it. The only
 * way to know is to write down that an attempt is running, and to see that note still there at the
 * start of the next attempt. The model that did it is then skipped, until the user picks a backend
 * again on purpose.
 */
class NpuGuard(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Writes down that an NPU attempt for [fileName] is running. */
    fun markAttempt(fileName: String) {
        // commit(), not apply(): the note has to be on disk before the native call, or a crash loses it.
        prefs.edit().putString(KEY_ATTEMPT, fileName).commit()
    }

    /** The attempt finished (or failed in a way we could catch): the note goes. */
    fun clearAttempt() {
        prefs.edit().remove(KEY_ATTEMPT).commit()
    }

    /** Whether this model crashed the NPU last time. */
    fun isBlocked(fileName: String): Boolean = prefs.getString(KEY_BLOCKED, null) == fileName

    /** The user picked a backend again: the model may be tried once more. */
    fun clearBlocked() {
        prefs.edit().remove(KEY_BLOCKED).commit()
    }

    /**
     * Called at the start of every load where the NPU is wanted: if the note of an earlier attempt is
     * still there, that attempt killed the process.
     */
    fun checkPreviousAttempt(fileName: String) {
        val attempt = prefs.getString(KEY_ATTEMPT, null) ?: return
        if (attempt == fileName) {
            prefs.edit().putString(KEY_BLOCKED, fileName).remove(KEY_ATTEMPT).commit()
        } else {
            // A different model: its note is stale, but it is still an attempt that did not finish.
            clearAttempt()
        }
    }

    companion object {
        private const val PREFS = "backend_guard"
        private const val KEY_ATTEMPT = "attempt"
        private const val KEY_BLOCKED = "blocked"
    }
}
