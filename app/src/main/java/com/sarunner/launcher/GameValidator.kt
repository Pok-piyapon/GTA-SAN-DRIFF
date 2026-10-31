package com.sarunner.launcher

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Validates a user-selected GTA SA PC folder through the Storage Access Framework.
 * Read-only: lists folders and reads the first 4 KB of gta_sa.exe to parse its PE header.
 * Nothing is executed and nothing is copied.
 */
class GameValidator(private val resolver: ContentResolver) {

    data class Entry(val name: String, val docId: String, val isDir: Boolean, val size: Long)

    enum class Level { OK, WARN, ERROR, INFO }

    data class Check(val level: Level, val message: String)

    data class Report(val checks: List<Check>) {
        val ok: Boolean get() = checks.none { it.level == Level.ERROR }
    }

    fun validate(treeUri: Uri): Report {
        val checks = mutableListOf<Check>()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val root = list(treeUri, rootId)
        if (root == null) {
            return Report(listOf(Check(Level.ERROR, "อ่านโฟลเดอร์ไม่ได้ (สิทธิ์เข้าถึงหมดหรือโฟลเดอร์ถูกย้าย)")))
        }

        fun find(entries: List<Entry>, name: String) =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

        // 1. Executable
        val exe = find(root, "gta_sa.exe")
        if (exe == null || exe.isDir) {
            checks += Check(Level.ERROR, "ไม่พบ gta_sa.exe ในโฟลเดอร์ที่เลือก (ต้องเลือกโฟลเดอร์หลักของเกม)")
        } else {
            checks += Check(Level.OK, "พบ gta_sa.exe (${exe.size / 1024} KB)")
            checks += inspectPe(treeUri, exe)
            checks += Check(
                Level.INFO,
                "ยังไม่ระบุเวอร์ชันเกม (1.0 US / Steam / อื่นๆ) — ยังไม่มีฐานข้อมูลที่ยืนยันแล้ว"
            )
        }

        // 2. Required folders and key files
        val required = listOf(
            "models" to "gta3.img",
            "data" to "gta.dat",
            "anim" to "anim.img",
            "audio" to null,
        )
        for ((dir, file) in required) {
            val d = find(root, dir)
            if (d == null || !d.isDir) {
                checks += Check(Level.ERROR, "ไม่พบโฟลเดอร์ $dir/")
                continue
            }
            if (file == null) {
                checks += Check(Level.OK, "พบ $dir/")
                continue
            }
            val children = list(treeUri, d.docId).orEmpty()
            checks += if (find(children, file) != null) {
                Check(Level.OK, "พบ $dir/$file")
            } else {
                Check(Level.ERROR, "ไม่พบ $dir/$file")
            }
        }

        // 3. Mods / DLL overrides that interact with the graphics path
        if (find(root, "d3d9.dll") != null) {
            checks += Check(
                Level.WARN,
                "พบ d3d9.dll ในโฟลเดอร์เกม (มักเป็น ENB/ReShade) → จะชนกับ DXVK ซึ่งใช้ชื่อไฟล์เดียวกัน"
            )
        }
        for (loader in listOf("dinput8.dll", "vorbisFile.dll")) {
            if (find(root, loader) != null) {
                checks += Check(Level.INFO, "พบ $loader (อาจเป็น ASI Loader) → ต้องทดสอบกับ Wine")
            }
        }
        if (find(root, "cleo")?.isDir == true) {
            checks += Check(Level.INFO, "พบโฟลเดอร์ cleo/ → ต้องทดสอบกับ Wine")
        }
        val asiCount = root.count { it.name.endsWith(".asi", ignoreCase = true) }
        if (asiCount > 0) {
            checks += Check(Level.INFO, "พบปลั๊กอิน .asi $asiCount ไฟล์ → ต้องทดสอบทีละตัว")
        }

        return Report(checks)
    }

    /** Reads the PE header and reports the target machine type. */
    private fun inspectPe(treeUri: Uri, exe: Entry): List<Check> {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, exe.docId)
        val header = ByteArray(4096)
        val read = try {
            resolver.openInputStream(uri)?.use { input ->
                var total = 0
                while (total < header.size) {
                    val n = input.read(header, total, header.size - total)
                    if (n <= 0) break
                    total += n
                }
                total
            } ?: -1
        } catch (t: Throwable) {
            return listOf(Check(Level.ERROR, "อ่าน gta_sa.exe ไม่ได้: ${t.message}"))
        }

        if (read < 64 || header[0] != 'M'.code.toByte() || header[1] != 'Z'.code.toByte()) {
            return listOf(Check(Level.ERROR, "gta_sa.exe ไม่ใช่ไฟล์ Windows (ไม่มีลายเซ็น MZ)"))
        }
        val peOffset = u32(header, 0x3C)
        if (peOffset < 0 || peOffset + 6 > read) {
            return listOf(Check(Level.ERROR, "ตำแหน่ง PE header ผิดปกติ ($peOffset)"))
        }
        val sig = String(header, peOffset.toInt(), 4, Charsets.ISO_8859_1)
        if (sig != "PE\u0000\u0000") {
            return listOf(Check(Level.ERROR, "ไม่พบลายเซ็น PE"))
        }
        val machine = u16(header, peOffset.toInt() + 4)
        return when (machine) {
            0x014C -> listOf(Check(Level.OK, "สถาปัตยกรรม: x86 32 บิต (i386) → ต้องใช้ Box64 + WoW64 หรือ Box86"))
            0x8664 -> listOf(Check(Level.WARN, "สถาปัตยกรรม: x86-64 — ไม่ตรงกับ GTA SA ต้นฉบับ อาจเป็นไฟล์ที่ถูกแก้ไข"))
            else -> listOf(Check(Level.ERROR, "สถาปัตยกรรมไม่รู้จัก: 0x${machine.toString(16)}"))
        }
    }

    private fun list(treeUri: Uri, parentDocId: String): List<Entry>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        return try {
            resolver.query(childrenUri, projection, null, null, null)?.use { c ->
                val out = mutableListOf<Entry>()
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    out += Entry(name, id, mime == DocumentsContract.Document.MIME_TYPE_DIR, size)
                }
                out
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun u16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, o: Int): Long =
        (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)
}
