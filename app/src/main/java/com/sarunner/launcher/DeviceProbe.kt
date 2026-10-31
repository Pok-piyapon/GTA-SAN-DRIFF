package com.sarunner.launcher

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import java.io.File

/** Collects device facts that decide which CPU-translation path the runtime can use. */
object DeviceProbe {

    data class Info(
        val lines: List<Pair<String, String>>,
        val supports32BitAbi: Boolean,
        val isArm64: Boolean,
        val pageSizeKb: Long,
    ) {
        fun cpuRecommendation(): List<String> {
            val out = mutableListOf<String>()
            if (!isArm64) {
                out += "CPU ไม่ใช่ arm64 → Box64 ไม่รองรับ เครื่องนี้ใช้ไม่ได้"
                return out
            }
            out += if (supports32BitAbi) {
                "ระบบรองรับแอป 32 บิต → ทดสอบได้ทั้ง Box86 และ Box64 + WoW64"
            } else {
                "ระบบไม่รองรับแอป 32 บิต → ใช้ Box86 ไม่ได้ ต้องใช้ Box64 + Wine WoW64"
            }
            if (pageSizeKb != 4L) {
                out += "เคอร์เนลใช้หน้าหน่วยความจำ ${pageSizeKb}KB (ไม่ใช่ 4KB) → เสี่ยงต่อ Box64/Wine ต้องทดสอบ"
            }
            return out
        }
    }

    fun collect(ctx: Context): Info {
        val lines = mutableListOf<Pair<String, String>>()

        lines += "Device" to "${Build.MANUFACTURER} ${Build.MODEL}"
        lines += "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        if (Build.VERSION.SDK_INT >= 31) {
            lines += "SoC" to "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
        }
        lines += "Hardware" to Build.HARDWARE

        val abis = Build.SUPPORTED_ABIS.toList()
        val abis32 = Build.SUPPORTED_32_BIT_ABIS.toList()
        lines += "ABIs" to abis.joinToString()
        lines += "32-bit ABIs" to (abis32.joinToString().ifEmpty { "(none)" })

        val cpuParts = readCpuParts()
        if (cpuParts.isNotEmpty()) lines += "CPU part IDs" to cpuParts.joinToString()

        val pageSize = try {
            Os.sysconf(OsConstants._SC_PAGESIZE) / 1024
        } catch (_: Throwable) { -1L }
        lines += "Page size" to "${pageSize} KB"

        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        lines += "RAM total" to "${mi.totalMem / (1024 * 1024)} MB"
        lines += "RAM available" to "${mi.availMem / (1024 * 1024)} MB"

        val stat = StatFs(ctx.filesDir.absolutePath)
        lines += "Free storage" to "${stat.availableBytes / (1024 * 1024)} MB"

        return Info(
            lines = lines,
            supports32BitAbi = abis32.isNotEmpty(),
            isArm64 = abis.contains("arm64-v8a"),
            pageSizeKb = pageSize,
        )
    }

    /** Unique "CPU part" values from /proc/cpuinfo (identifies core types). */
    private fun readCpuParts(): List<String> = try {
        File("/proc/cpuinfo").readLines()
            .filter { it.startsWith("CPU part") }
            .map { it.substringAfter(":").trim() }
            .distinct()
    } catch (_: Throwable) {
        emptyList()
    }
}
