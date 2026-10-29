package com.smnexstudio.panelglass.core.engine

import com.smnexstudio.panelglass.core.engine.local.LocalModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every on-device model is downloaded from an https URL pinned to the commit its SHA-256 belongs to: a branch name
 * (`main`) would let the file change under the hash and every download fail its check, or worse, pass a different file.
 */
class ModelPinningTest {
    private val pinned = Regex("""^https://huggingface\.co/[^/]+/[^/]+/resolve/[0-9a-f]{40}/[^/]+$""")
    private val sha256 = Regex("[0-9a-f]{64}")

    @Test fun everyModelUrlIsPinnedToACommitWithItsHash() {
        for (m in LocalModel.entries) {
            assertTrue(pinned.matches(m.url), "${m.name}: ${m.url}")
            assertTrue(sha256.matches(m.sha256), "${m.name}: hash")
            assertTrue(m.bytes > 0 && m.minValidBytes in 1 until m.bytes, m.name)
        }
    }

    @Test fun modelsHaveDistinctFilesHashesAndEngines() {
        val all = LocalModel.entries
        assertEquals(all.size, all.map { it.fileName }.toSet().size)
        assertEquals(all.size, all.map { it.sha256 }.toSet().size)
        assertEquals(all.size, all.map { it.engineId }.toSet().size)
        all.forEach { assertEquals(it, LocalModel.of(it.engineId)) }
    }

    /** A retired model's file is deleted at start; it must never be a file a current model uses. */
    @Test fun retiredFilesAreNoCurrentModelsFile() {
        val current = LocalModel.entries.map { it.fileName }.toSet()
        assertTrue(LocalModel.RETIRED_FILES.none { it in current })
    }
}
