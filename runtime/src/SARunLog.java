package com.winlator;

import android.content.Context;

import com.winlator.core.ProcessHelper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Date;

/**
 * Writes the Wine/Box64 output of the current game run to
 * /storage/emulated/0/SARunner/last_run.log so it can be read after a crash.
 * Attached from XServerDisplayActivity (see runtime/apply_overlay.py).
 */
public final class SARunLog {
    public static final String DIR = "/storage/emulated/0/SARunner";
    private static BufferedWriter writer;

    private SARunLog() {}

    public static File file() {
        return new File(DIR, "last_run.log");
    }

    public static synchronized void attach(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("sa_runner", Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("capture_logs", true);
        if (!enabled) return;
        close();
        try {
            File dir = new File(DIR);
            dir.mkdirs();
            writer = new BufferedWriter(new FileWriter(file(), false));
            writer.write("SA Runner run log " + new Date() + "\n");
            writer.write("graphics mode: " + prefs.getString("graphics_mode_label", "?") + "\n");
            writer.flush();
        }
        catch (IOException e) {
            writer = null;
            return;
        }
        ProcessHelper.addDebugCallback(SARunLog::write);
    }

    private static synchronized void write(String line) {
        if (writer == null) return;
        try {
            writer.write(line);
            writer.write('\n');
            writer.flush();
        }
        catch (IOException ignored) {
        }
    }

    public static synchronized void close() {
        if (writer == null) return;
        try {
            writer.close();
        }
        catch (IOException ignored) {
        }
        writer = null;
    }

    /** Last maxBytes of the log as text, or a message if there is no log yet. */
    public static String tail(int maxBytes) {
        File f = file();
        if (!f.isFile()) return "ยังไม่มี log การรัน (" + f.getPath() + ")";
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - maxBytes);
            byte[] buf = new byte[(int) (len - start)];
            raf.seek(start);
            raf.readFully(buf);
            String text = new String(buf, "UTF-8");
            return (start > 0 ? "…(ตัดส่วนต้นออก)\n" : "") + text;
        }
        catch (IOException e) {
            return "อ่าน log ไม่ได้: " + e.getMessage();
        }
    }
}
