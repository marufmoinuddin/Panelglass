package com.smnexstudio.panelglass.core.engine

import com.smnexstudio.panelglass.core.engine.local.NpuStatus
import com.smnexstudio.panelglass.core.engine.local.NpuSupport
import org.junit.Assert.assertEquals
import org.junit.Test

/** Whether a model file was compiled for this phone's chip: the pure part of the NPU check. */
class NpuSupportTest {
    @Test fun aFileBuiltForThisChipIsUsable() {
        assertEquals(
            NpuStatus.USABLE,
            NpuSupport.fileStatus("Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm", "SM8750"),
        )
    }

    @Test fun aFileBuiltForAnotherChipIsNot() {
        assertEquals(
            NpuStatus.MODEL_NOT_COMPILED,
            NpuSupport.fileStatus("Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm", "SM8650"),
        )
    }

    @Test fun aPlainModelIsNotBuiltForAnyNpu() {
        assertEquals(
            NpuStatus.MODEL_NOT_COMPILED,
            NpuSupport.fileStatus("qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm", "SM8750"),
        )
    }

    @Test fun theChipIsMatchedWithoutRegardToCase() {
        assertEquals(NpuStatus.USABLE, NpuSupport.fileStatus("model_sm8750.litertlm", "sm8750"))
        assertEquals(NpuStatus.USABLE, NpuSupport.fileStatus("MODEL_SM8750.LITERTLM", "SM8750"))
    }

    @Test fun anUnknownChipIsNeverUsable() {
        assertEquals(NpuStatus.MODEL_NOT_COMPILED, NpuSupport.fileStatus("model_sm8750.litertlm", ""))
    }
}
