package com.smnexstudio.panelglass.core.engine.local

import com.smnexstudio.panelglass.core.model.ComputeBackend

/** Where a model can run. */
enum class BackendKind { NPU, GPU, CPU }

/**
 * Why the backend that ran is not the one that was asked for. The app shows these under "Running on …".
 */
enum class BackendNote {
    NONE,
    /** This phone is not a Qualcomm one, so there is no NPU to use. */
    NPU_NOT_QUALCOMM,
    /** The Qualcomm NPU libraries are not in this build. */
    NPU_LIBS_MISSING,
    /** This model file was not compiled for this phone's chip. */
    NPU_MODEL_NOT_COMPILED,
    /** The NPU crashed last time, so it is skipped until the user picks a backend again. */
    NPU_BLOCKED,
    /** The NPU did not start; the GPU or the CPU was used instead. */
    NPU_INIT_FAILED,
    /** The GPU did not start; the CPU was used instead. */
    GPU_INIT_FAILED,
}

/** Whether the NPU can be used at all for a given model on this phone. */
enum class NpuStatus { USABLE, NOT_QUALCOMM, LIBS_MISSING, MODEL_NOT_COMPILED, BLOCKED }

/**
 * Which backends to try, in order, and why the first one may be skipped. The first backend that starts wins.
 */
data class BackendPlan(val order: List<BackendKind>, val note: BackendNote = BackendNote.NONE)

/**
 * The rule that turns the user's choice into the order the backends are tried in.
 *
 * Pure: it knows nothing about the phone. The caller passes what it found there.
 */
object BackendPlanner {
    fun plan(choice: ComputeBackend, lowRam: Boolean, emulator: Boolean, npu: NpuStatus): BackendPlan {
        // An emulator has no GPU and no NPU worth speaking of.
        if (emulator) return BackendPlan(listOf(BackendKind.CPU))
        return when (choice) {
            // Automatic never uses the NPU: a model that was not compiled for the chip would not load on it anyway.
            ComputeBackend.AUTO -> if (lowRam) BackendPlan(listOf(BackendKind.CPU)) else BackendPlan(listOf(BackendKind.GPU, BackendKind.CPU))
            ComputeBackend.CPU -> BackendPlan(listOf(BackendKind.CPU))
            ComputeBackend.GPU -> BackendPlan(listOf(BackendKind.GPU, BackendKind.CPU))
            ComputeBackend.NPU -> if (npu == NpuStatus.USABLE) {
                BackendPlan(listOf(BackendKind.NPU, BackendKind.GPU, BackendKind.CPU))
            } else {
                BackendPlan(listOf(BackendKind.GPU, BackendKind.CPU), noteFor(npu))
            }
        }
    }

    /** The note that explains why the NPU was skipped. */
    private fun noteFor(npu: NpuStatus): BackendNote = when (npu) {
        NpuStatus.USABLE -> BackendNote.NONE
        NpuStatus.NOT_QUALCOMM -> BackendNote.NPU_NOT_QUALCOMM
        NpuStatus.LIBS_MISSING -> BackendNote.NPU_LIBS_MISSING
        NpuStatus.MODEL_NOT_COMPILED -> BackendNote.NPU_MODEL_NOT_COMPILED
        NpuStatus.BLOCKED -> BackendNote.NPU_BLOCKED
    }
}
