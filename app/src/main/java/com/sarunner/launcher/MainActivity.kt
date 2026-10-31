package com.sarunner.launcher

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Phase 3 prototype launcher.
 * - Probes CPU / Android / Vulkan capabilities.
 * - Lets the user pick their own GTA SA PC folder (SAF) and validates it read-only.
 * - Shows whether a compatibility runtime is installed (not yet: Phase 4).
 * - Keeps a diagnostic log that the user can copy or share.
 * It does NOT run the game.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var gameView: TextView
    private lateinit var logView: TextView
    private lateinit var launchButton: Button

    private val log = StringBuilder()
    private val prefs by lazy { getSharedPreferences("launcher", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        runProbe()
        prefs.getString(KEY_TREE, null)?.let { validateGame(Uri.parse(it)) }
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        col.addView(TextView(this).apply {
            text = "SA Runner"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "ต้นแบบ 0.1 — ตรวจเครื่องและไฟล์เกมเท่านั้น ยังไม่รันเกม"
            textSize = 13f
        })

        col.addView(header("สถานะเครื่อง"))
        statusView = body()
        col.addView(statusView)

        col.addView(header("ไฟล์เกม"))
        gameView = body().apply { text = "ยังไม่ได้เลือกโฟลเดอร์เกม" }
        col.addView(gameView)

        col.addView(button("เลือกโฟลเดอร์ GTA San Andreas (PC)") { pickFolder() })
        col.addView(button("ตรวจเครื่องอีกครั้ง") { runProbe() })

        launchButton = button("เปิดเกม") { }.apply {
            isEnabled = false
            text = "เปิดเกม (ยังไม่มี runtime — เฟส 4)"
        }
        col.addView(launchButton)

        col.addView(header("Log"))
        col.addView(button("คัดลอก log") { copyLog() })
        col.addView(button("แชร์ log") { shareLog() })
        logView = body().apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        col.addView(logView)

        return ScrollView(this).apply { addView(col) }
    }

    private fun header(t: String) = TextView(this).apply {
        text = t
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, (20 * resources.displayMetrics.density).toInt(), 0, 8)
    }

    private fun body() = TextView(this).apply { textSize = 14f }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    // ---------------------------------------------------------------- Probe

    private fun runProbe() {
        statusView.text = "กำลังตรวจ…"
        thread(name = "probe") {
            val dev = DeviceProbe.collect(this)
            val vk = VulkanProbe().run()
            val runtime = RuntimeStatus.check(filesDir)

            val sb = StringBuilder()
            sb.appendLine("CPU:")
            dev.cpuRecommendation().forEach { sb.appendLine("• $it") }
            sb.appendLine()
            sb.appendLine("GPU: ${vk.deviceName ?: "ไม่พบ"} (${vk.gpuFamily})")
            sb.appendLine("Vulkan: ${vk.apiVersion ?: "-"}")
            vk.graphicsRecommendation().forEach { sb.appendLine("• $it") }
            sb.appendLine()
            sb.appendLine("Runtime: ${runtime.summary}")

            runOnUiThread {
                statusView.text = sb.toString().trimEnd()
                logSection("Device") { dev.lines.forEach { (k, v) -> appendLine("$k: $v") } }
                logSection("Vulkan") { vk.raw.toSortedMap().forEach { (k, v) -> appendLine("$k=$v") } }
                logSection("Runtime") { appendLine(runtime.summary) }
            }
        }
    }

    // ---------------------------------------------------------------- Game folder

    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_TREE)
    }

    @Deprecated("Plain Activity API; fine for this dependency-free prototype.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TREE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Permission will only last for this session; validation still works now.
        }
        prefs.edit().putString(KEY_TREE, uri.toString()).apply()
        validateGame(uri)
    }

    private fun validateGame(uri: Uri) {
        gameView.text = "กำลังตรวจไฟล์…"
        thread(name = "validate") {
            val report = GameValidator(contentResolver).validate(uri)
            val folderName = folderDisplayName(uri)
            val text = buildString {
                appendLine("โฟลเดอร์: $folderName")
                report.checks.forEach { c ->
                    val mark = when (c.level) {
                        GameValidator.Level.OK -> "✓"
                        GameValidator.Level.WARN -> "!"
                        GameValidator.Level.ERROR -> "✗"
                        GameValidator.Level.INFO -> "i"
                    }
                    appendLine("$mark ${c.message}")
                }
                appendLine()
                append(if (report.ok) "ผลรวม: ไฟล์ครบตามที่ตรวจ" else "ผลรวม: ไฟล์ไม่ครบ ดูรายการ ✗")
            }
            runOnUiThread {
                gameView.text = text
                // Only the folder's display name is logged, not the full storage path.
                logSection("Game folder") { append(text).appendLine() }
            }
        }
    }

    private fun folderDisplayName(tree: Uri): String = try {
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        contentResolver.query(
            docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "(ไม่ทราบชื่อ)"
    } catch (_: Throwable) {
        "(ไม่ทราบชื่อ)"
    }

    // ---------------------------------------------------------------- Log

    private fun logSection(title: String, block: StringBuilder.() -> Unit) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        log.appendLine("=== $title [$ts] ===")
        log.block()
        log.appendLine()
        logView.text = log.toString()
        try {
            val dir = File(filesDir, "logs").apply { mkdirs() }
            File(dir, "probe.txt").writeText(log.toString())
        } catch (_: Throwable) {
        }
    }

    private fun fullLogText() = buildString {
        appendLine("SA Runner ${BuildConfigInfo.VERSION} diagnostic log")
        append(log)
    }

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("SA Runner log", fullLogText()))
        Toast.makeText(this, "คัดลอก log แล้ว", Toast.LENGTH_SHORT).show()
    }

    private fun shareLog() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, fullLogText())
        }
        startActivity(Intent.createChooser(send, "แชร์ log"))
    }

    companion object {
        private const val REQ_TREE = 1001
        private const val KEY_TREE = "game_tree_uri"
    }
}

/** Avoids enabling BuildConfig generation just for one string. */
object BuildConfigInfo {
    const val VERSION = "0.1.0-probe"
}
