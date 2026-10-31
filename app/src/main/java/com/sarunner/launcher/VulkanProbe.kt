package com.sarunner.launcher

/**
 * Thin JNI wrapper around vulkan_probe.cpp.
 * The native side returns "key=value" lines; this class parses them and derives a
 * graphics-path recommendation. Nothing here is a compatibility guarantee — it only
 * reports what the driver advertises.
 */
class VulkanProbe {

    private external fun nativeProbe(): String

    data class Result(
        val raw: Map<String, String>,
        val error: String?,
        val deviceName: String?,
        val apiVersion: String?,
        val vendorId: String?,
    ) {
        /** Extensions DXVK 3.x lists as required on its wiki "Driver support" page. */
        val dxvk3RequiredExtensions = listOf(
            "VK_EXT_depth_clip_enable",
            "VK_KHR_maintenance5",
            "VK_KHR_maintenance6",
            "VK_KHR_load_store_op_none",
            "VK_EXT_robustness2",
            "VK_EXT_transform_feedback",
        )

        fun hasExt(name: String) = raw["dev0.ext.$name"] == "yes"

        val gpuFamily: String
            get() = when (vendorId?.uppercase()) {
                "0X00005143" -> "Qualcomm Adreno"
                "0X000013B5" -> "ARM Mali"
                "0X00001010" -> "Imagination PowerVR"
                "0X00001002" -> "AMD (Samsung Xclipse)"
                else -> "Unknown (${vendorId ?: "-"})"
            }

        private fun apiAtLeast(major: Int, minor: Int): Boolean {
            val parts = apiVersion?.split(".") ?: return false
            val ma = parts.getOrNull(0)?.toIntOrNull() ?: return false
            val mi = parts.getOrNull(1)?.toIntOrNull() ?: return false
            return ma > major || (ma == major && mi >= minor)
        }

        /** Human-readable recommendation, clearly labelled as untested. */
        fun graphicsRecommendation(): List<String> {
            if (error != null || deviceName == null) {
                return listOf("ไม่พบ Vulkan ที่ใช้งานได้ → ใช้ได้เฉพาะ WineD3D (OpenGL) ซึ่งช้ากว่า")
            }
            val out = mutableListOf<String>()
            val missing3 = dxvk3RequiredExtensions.filterNot { hasExt(it) }
            if (apiAtLeast(1, 4) && missing3.isEmpty()) {
                out += "ผ่านเงื่อนไขขั้นต่ำของ DXVK 3.x ตาม wiki (ยังต้องทดสอบจริง)"
            } else {
                out += "ไม่ผ่านเงื่อนไข DXVK 3.x" +
                    (if (!apiAtLeast(1, 4)) " (Vulkan ต่ำกว่า 1.4)" else "") +
                    (if (missing3.isNotEmpty()) " ขาด: ${missing3.joinToString()}" else "")
            }
            if (apiAtLeast(1, 1)) {
                out += "เข้าเงื่อนไข Vulkan 1.1 ของ DXVK 1.10.3 → ใช้เป็นตัวแรกที่ทดสอบ"
            } else {
                out += "Vulkan ต่ำกว่า 1.1 → DXVK ใช้ไม่ได้ ต้องใช้ WineD3D"
            }
            out += when (gpuFamily) {
                "Qualcomm Adreno" -> "ไดรเวอร์: ทดสอบ Turnip ก่อน ถ้าไม่ได้ใช้ไดรเวอร์เครื่องผ่าน Vortek"
                "ARM Mali" -> "ไดรเวอร์: ใช้ไดรเวอร์เครื่องผ่าน Vortek (Turnip ใช้กับ Mali ไม่ได้)"
                else -> "ไดรเวอร์: ใช้ไดรเวอร์เครื่องผ่าน Vortek (ยังไม่มีข้อมูล GPU นี้)"
            }
            return out
        }
    }

    fun run(): Result {
        val text = try {
            nativeProbe()
        } catch (t: Throwable) {
            "error=${t.javaClass.simpleName}: ${t.message}"
        }
        val map = text.lineSequence()
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }
            .toMap()
        return Result(
            raw = map,
            error = map["error"],
            deviceName = map["dev0.name"],
            apiVersion = map["dev0.api_version"],
            vendorId = map["dev0.vendor_id"],
        )
    }

    companion object {
        init {
            System.loadLibrary("saprobe")
        }
    }
}
