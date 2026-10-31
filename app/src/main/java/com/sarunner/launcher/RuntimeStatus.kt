package com.sarunner.launcher

import java.io.File

/**
 * Reports whether the compatibility runtime (Box64 + Wine + DXVK + drivers) is installed
 * in app-private storage. In this prototype nothing installs it yet; Phase 4 will unpack
 * the runtime to files/runtime/ and write files/runtime/manifest.json.
 */
object RuntimeStatus {

    data class Status(val installed: Boolean, val summary: String)

    fun check(filesDir: File): Status {
        val manifest = File(filesDir, "runtime/manifest.json")
        return if (manifest.isFile) {
            Status(true, "ติดตั้งแล้ว (${manifest.length()} bytes manifest)")
        } else {
            Status(false, "ยังไม่ติดตั้ง — จะเพิ่มในเฟส 4")
        }
    }
}
