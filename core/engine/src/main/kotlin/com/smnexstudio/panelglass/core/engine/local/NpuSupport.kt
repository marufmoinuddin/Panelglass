package com.smnexstudio.panelglass.core.engine.local

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Whether the NPU can be used at all, and why not when it cannot.
 *
 * The NPU is experimental. Three things have to hold: the phone has a
 * Qualcomm NPU, the QNN libraries are in this build, and the model file was
 * compiled ahead-of-time for this phone's own chip. A file built for another
 * chip will not load on the NPU at all.
 */
object NpuSupport {
    /** True on a Qualcomm SoC. SOC_MANUFACTURER is "QTI" on Snapdragon phones. */
    fun isQualcomm(): Boolean =
        Build.SOC_MANUFACTURER.equals("QTI", true) || Build.SOC_MODEL.startsWith("SM", true)

    /** Whether the QNN libraries were bundled into this build. */
    fun libsPresent(context: Context): Boolean =
        File(context.applicationInfo.nativeLibraryDir, "libQnnHtp.so").exists()

    /**
     * Whether this model file looks compiled for this SoC. Pure: the file name carries the chip it was
     * built for (`…_sm8750.litertlm`), and the SoC id is matched case-insensitively.
     */
    fun fileStatus(fileName: String, socModel: String): NpuStatus {
        val soc = socModel.trim().lowercase()
        if (soc.isEmpty()) return NpuStatus.MODEL_NOT_COMPILED
        return if (fileName.lowercase().contains(soc)) NpuStatus.USABLE else NpuStatus.MODEL_NOT_COMPILED
    }

    /** Whether the phone could use an NPU at all (the model may still not be built for it). */
    fun deviceAvailable(context: Context): Boolean = isQualcomm() && libsPresent(context)

    /**
     * The NPU's status for this model on this phone: the first thing that is not in order.
     */
    fun status(context: Context, fileName: String): NpuStatus {
        if (!isQualcomm()) return NpuStatus.NOT_QUALCOMM
        if (!libsPresent(context)) return NpuStatus.LIBS_MISSING
        val model = fileStatus(fileName, Build.SOC_MODEL)
        if (model != NpuStatus.USABLE) return model
        // A model whose NPU attempt killed the process last time is skipped until the user picks a backend again.
        return if (NpuGuard(context).isBlocked(fileName)) NpuStatus.BLOCKED else NpuStatus.USABLE
    }
}
