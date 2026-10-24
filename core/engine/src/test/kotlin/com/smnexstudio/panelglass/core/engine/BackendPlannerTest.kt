package com.smnexstudio.panelglass.core.engine

import com.smnexstudio.panelglass.core.engine.local.BackendKind
import com.smnexstudio.panelglass.core.engine.local.BackendNote
import com.smnexstudio.panelglass.core.engine.local.BackendPlan
import com.smnexstudio.panelglass.core.engine.local.BackendPlanner
import com.smnexstudio.panelglass.core.engine.local.NpuStatus
import com.smnexstudio.panelglass.core.model.ComputeBackend
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The rule that turns the user's choice into the order the backends are tried in. */
class BackendPlannerTest {
    private fun plan(choice: ComputeBackend, lowRam: Boolean = false, emulator: Boolean = false, npu: NpuStatus = NpuStatus.NOT_QUALCOMM) =
        BackendPlanner.plan(choice, lowRam, emulator, npu)

    @Test fun automaticTakesTheCpuOnAPhoneWithLittleRam() {
        assertEquals(BackendPlan(listOf(BackendKind.CPU)), plan(ComputeBackend.AUTO, lowRam = true))
    }

    @Test fun automaticTakesTheGpuOnAPhoneWithEnoughRam() {
        assertEquals(BackendPlan(listOf(BackendKind.GPU, BackendKind.CPU)), plan(ComputeBackend.AUTO))
    }

    @Test fun automaticNeverUsesTheNpu() {
        for (lowRam in listOf(true, false)) {
            assertTrue(BackendKind.NPU !in plan(ComputeBackend.AUTO, lowRam = lowRam, npu = NpuStatus.USABLE).order)
        }
    }

    @Test fun cpuIsTheOnlyBackend() {
        assertEquals(BackendPlan(listOf(BackendKind.CPU)), plan(ComputeBackend.CPU))
    }

    @Test fun gpuFallsBackToTheCpu() {
        assertEquals(BackendPlan(listOf(BackendKind.GPU, BackendKind.CPU)), plan(ComputeBackend.GPU))
    }

    @Test fun anEmulatorAlwaysUsesTheCpu() {
        for (choice in ComputeBackend.entries) {
            assertEquals(
                BackendPlan(listOf(BackendKind.CPU)),
                plan(choice, emulator = true, npu = NpuStatus.USABLE),
                choice.name,
            )
        }
    }

    @Test fun theNpuIsTriedFirstWhenItCanBeUsed() {
        val p = plan(ComputeBackend.NPU, npu = NpuStatus.USABLE)
        assertEquals(listOf(BackendKind.NPU, BackendKind.GPU, BackendKind.CPU), p.order)
        assertEquals(BackendNote.NONE, p.note)
    }

    @Test fun anUnusableNpuFallsBackToTheGpuWithTheMatchingNote() {
        val cases = mapOf(
            NpuStatus.NOT_QUALCOMM to BackendNote.NPU_NOT_QUALCOMM,
            NpuStatus.LIBS_MISSING to BackendNote.NPU_LIBS_MISSING,
            NpuStatus.MODEL_NOT_COMPILED to BackendNote.NPU_MODEL_NOT_COMPILED,
            NpuStatus.BLOCKED to BackendNote.NPU_BLOCKED,
        )
        for ((status, note) in cases) {
            val p = plan(ComputeBackend.NPU, npu = status)
            assertEquals(listOf(BackendKind.GPU, BackendKind.CPU), p.order, status.name)
            assertEquals(note, p.note, status.name)
        }
    }
}
