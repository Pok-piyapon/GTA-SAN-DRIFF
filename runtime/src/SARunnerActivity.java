package com.winlator;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.GraphicsDrivers;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.RootFSInstaller;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Locale;

/**
 * SA Runner launcher (Phase 4).
 * Sits in front of the Winlator runtime: installs the system files, creates one container
 * configured for GTA San Andreas, validates the user's own game folder and starts the game
 * in XServerDisplayActivity. Winlator's own UI stays reachable through "Advanced settings".
 */
public class SARunnerActivity extends AppCompatActivity {
    private static final String CONTAINER_NAME = "GTA San Andreas (SA Runner)";
    private static final String PREFS = "sa_runner";
    private static final String KEY_EXE = "exe_path";
    private static final String KEY_SETUP = "setup_path";
    private static final int REQ_STORAGE = 2001;
    /** Whole internal storage, added as drive F: so the game can live anywhere on it. */
    private static final String STORAGE_ROOT = "/storage/emulated/0";

    private TextView systemView;
    private TextView gameView;
    private TextView logView;
    private EditText pathInput;
    private EditText setupInput;
    private TextView setupView;
    private Button setupButton;
    private Button installButton;
    private Button launchButton;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder log = new StringBuilder();
    private boolean installing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        pathInput.setText(prefs.getString(KEY_EXE, STORAGE_ROOT + "/GTA San Andreas/gta_sa.exe"));
        setupInput.setText(prefs.getString(KEY_SETUP, ""));

        if (!hasStoragePermission()) {
            ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQ_STORAGE);
        }
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        int pad = dp(16);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("SA Runner");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("GTA San Andreas (PC) บน Android ผ่าน Wine + Box64 + DXVK — รุ่นทดสอบ");
        col.addView(sub);

        col.addView(header("1. ระบบ"));
        systemView = body();
        col.addView(systemView);
        installButton = button("ติดตั้งไฟล์ระบบ (ครั้งแรกครั้งเดียว)", v -> installSystem());
        col.addView(installButton);

        col.addView(header("ติดตั้งเกมจาก setup.exe (ถ้ายังไม่มีโฟลเดอร์เกม)"));
        TextView setupHint = body();
        setupHint.setText("แตก zip ของคุณในมือถือก่อน แล้วระบุไฟล์ setup.exe\n"
            + "ตอนตัวติดตั้งถามที่ติดตั้ง ให้เลือกไดรฟ์ F: (= ที่เก็บข้อมูลหลัก) เช่น F:\\GTA San Andreas");
        col.addView(setupHint);
        setupInput = new EditText(this);
        setupInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        setupInput.setTextSize(13);
        setupInput.setHint(STORAGE_ROOT + "/Download/.../setup.exe");
        col.addView(setupInput);
        col.addView(button("ค้นหา setup.exe อัตโนมัติ", v -> autoFindSetup()));
        setupView = body();
        col.addView(setupView);
        setupButton = button("รันตัวติดตั้ง", v -> runSetup());
        col.addView(setupButton);

        col.addView(header("2. ไฟล์เกม"));
        TextView hint = body();
        hint.setText("ตำแหน่งไฟล์ gta_sa.exe ในเครื่อง (ไฟล์เกมของคุณเอง)");
        col.addView(hint);
        pathInput = new EditText(this);
        pathInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        pathInput.setTextSize(13);
        col.addView(pathInput);
        col.addView(button("ค้นหา gta_sa.exe อัตโนมัติ", v -> autoFind()));
        col.addView(button("ตรวจไฟล์เกม", v -> { savePath(); refresh(); }));
        gameView = body();
        col.addView(gameView);

        col.addView(header("3. เล่น"));
        launchButton = button("เปิดเกม", v -> launch());
        col.addView(launchButton);
        col.addView(button("ตั้งค่าขั้นสูง (Winlator)", v ->
            startActivity(new Intent(this, MainActivity.class))));

        col.addView(header("Log"));
        logView = body();
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(11);
        logView.setTextIsSelectable(true);
        col.addView(logView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        return scroll;
    }

    private TextView header(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(18);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(20), 0, dp(6));
        return tv;
    }

    private TextView body() {
        TextView tv = new TextView(this);
        tv.setTextSize(14);
        return tv;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void log(String line) {
        log.append(line).append('\n');
        logView.setText(log.toString());
    }

    // ------------------------------------------------------------------ State

    private boolean hasStoragePermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isSystemReady() {
        RootFS rootFS = RootFS.find(this);
        return rootFS.isValid() && rootFS.getVersion() >= RootFSInstaller.LATEST_VERSION;
    }

    private void refresh() {
        boolean perm = hasStoragePermission();
        boolean system = isSystemReady();

        StringBuilder s = new StringBuilder();
        s.append(perm ? "✓ อนุญาตเข้าถึงไฟล์แล้ว\n" : "✗ ยังไม่อนุญาตเข้าถึงไฟล์ (ต้องใช้เพื่ออ่านไฟล์เกม)\n");
        if (installing) s.append("… กำลังติดตั้งไฟล์ระบบ\n");
        else s.append(system ? "✓ ไฟล์ระบบพร้อม (Wine, Box64, DXVK, ไดรเวอร์)\n" : "✗ ยังไม่ได้ติดตั้งไฟล์ระบบ\n");
        s.append("ไดรเวอร์ Vulkan ที่จะใช้: ").append(GraphicsDrivers.getName(
            GraphicsDrivers.getDefaultDriver(this).split(",")[0]));
        systemView.setText(s.toString());
        installButton.setVisibility(system || installing ? View.GONE : View.VISIBLE);

        GameCheck game = perm ? checkGame(currentPath()) : null;
        gameView.setText(game == null ? "อนุญาตเข้าถึงไฟล์ก่อน แล้วกด \"ตรวจไฟล์เกม\"" : game.report);

        launchButton.setEnabled(perm && system && !installing && game != null && game.ok);

        String setupPath = setupInput.getText().toString().trim();
        String setupProblem = setupPath.isEmpty() ? "ยังไม่ได้ระบุ setup.exe" : checkSetup(setupPath);
        setupView.setText(setupProblem == null ? "✓ พร้อมรันตัวติดตั้ง" : setupProblem);
        setupButton.setEnabled(perm && system && !installing && setupProblem == null);
    }

    private String currentPath() {
        return pathInput.getText().toString().trim();
    }

    private void savePath() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_EXE, currentPath()).apply();
    }

    // ------------------------------------------------------------------ Install

    private void installSystem() {
        if (installing) return;
        installing = true;
        log("เริ่มติดตั้งไฟล์ระบบ");
        RootFSInstaller.install(this);
        refresh();
        pollInstall();
    }

    private void pollInstall() {
        handler.postDelayed(() -> {
            if (isSystemReady()) {
                installing = false;
                log("ติดตั้งไฟล์ระบบเสร็จ");
                refresh();
            }
            else pollInstall();
        }, 1000);
    }

    // ------------------------------------------------------------------ Game files

    private static final class GameCheck {
        final boolean ok;
        final String report;
        GameCheck(boolean ok, String report) { this.ok = ok; this.report = report; }
    }

    private GameCheck checkGame(String exePath) {
        StringBuilder r = new StringBuilder();
        boolean ok = true;
        File exe = new File(exePath);

        if (!exe.isFile()) {
            return new GameCheck(false, "✗ ไม่พบไฟล์: " + exePath);
        }
        if (!exePath.startsWith(STORAGE_ROOT + "/")) {
            r.append("✗ ไฟล์ต้องอยู่ใน " + STORAGE_ROOT + "\n");
            ok = false;
        }
        r.append("✓ พบ ").append(exe.getName()).append(" (").append(exe.length() / 1024).append(" KB)\n");

        int machine = readPeMachine(exe);
        if (machine == 0x014C) r.append("✓ เป็นโปรแกรม Windows x86 32 บิต\n");
        else if (machine == -1) { r.append("✗ ไม่ใช่ไฟล์ Windows ที่อ่านได้\n"); ok = false; }
        else r.append(String.format(Locale.US, "! สถาปัตยกรรมไม่ตรงกับต้นฉบับ (0x%X)\n", machine));

        File dir = exe.getParentFile();
        String[][] required = {{"models", "gta3.img"}, {"data", "gta.dat"}, {"anim", "anim.img"}, {"audio", null}};
        for (String[] req : required) {
            File sub = findChild(dir, req[0]);
            if (sub == null || !sub.isDirectory()) { r.append("✗ ไม่พบโฟลเดอร์ ").append(req[0]).append("/\n"); ok = false; continue; }
            if (req[1] == null) { r.append("✓ พบ ").append(req[0]).append("/\n"); continue; }
            if (findChild(sub, req[1]) == null) { r.append("✗ ไม่พบ ").append(req[0]).append('/').append(req[1]).append('\n'); ok = false; }
            else r.append("✓ พบ ").append(req[0]).append('/').append(req[1]).append('\n');
        }
        if (findChild(dir, "d3d9.dll") != null) {
            r.append("! พบ d3d9.dll ในโฟลเดอร์เกม (ENB/ReShade) จะชนกับ DXVK — แนะนำให้ย้ายออกก่อนทดสอบ\n");
        }
        r.append(ok ? "พร้อมเปิดเกม" : "ไฟล์ยังไม่ครบ");
        return new GameCheck(ok, r.toString());
    }

    private static File findChild(File dir, String name) {
        if (dir == null) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) if (f.getName().equalsIgnoreCase(name)) return f;
        return null;
    }

    /** Returns the PE "Machine" field, or -1 if the file is not a readable PE image. */
    private static int readPeMachine(File exe) {
        byte[] h = new byte[4096];
        int n;
        try (FileInputStream in = new FileInputStream(exe)) {
            n = in.read(h);
        }
        catch (Exception e) {
            return -1;
        }
        if (n < 64 || h[0] != 'M' || h[1] != 'Z') return -1;
        int pe = (h[0x3C] & 0xFF) | (h[0x3D] & 0xFF) << 8 | (h[0x3E] & 0xFF) << 16 | (h[0x3F] & 0xFF) << 24;
        if (pe < 0 || pe + 6 > n) return -1;
        if (h[pe] != 'P' || h[pe + 1] != 'E' || h[pe + 2] != 0 || h[pe + 3] != 0) return -1;
        return (h[pe + 4] & 0xFF) | (h[pe + 5] & 0xFF) << 8;
    }

    private void autoFind() {
        if (!hasStoragePermission()) { log("ยังไม่ได้รับอนุญาตเข้าถึงไฟล์"); return; }
        log("กำลังค้นหา gta_sa.exe ...");
        new Thread(() -> {
            ArrayList<File> found = new ArrayList<>();
            File root = new File(STORAGE_ROOT);
            search(root, 0, found);
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!downloads.getPath().startsWith(root.getPath())) search(downloads, 0, found);
            runOnUiThread(() -> {
                if (found.isEmpty()) log("ไม่พบ gta_sa.exe (ค้นลึก 4 ชั้นจาก " + STORAGE_ROOT + ")");
                else {
                    for (File f : found) log("พบ: " + f.getPath());
                    pathInput.setText(found.get(0).getPath());
                    savePath();
                }
                refresh();
            });
        }).start();
    }

    private static void search(File dir, int depth, ArrayList<File> out) {
        searchName(dir, depth, "gta_sa.exe", out);
    }

    private static void searchName(File dir, int depth, String target, ArrayList<File> out) {
        if (depth > 4 || out.size() >= 5) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (f.isDirectory()) {
                if (name.startsWith(".") || name.equals("Android")) continue;
                searchName(f, depth + 1, target, out);
            }
            else if (name.equalsIgnoreCase(target)) out.add(f);
        }
    }

    // ------------------------------------------------------------------ Launch

    private void launch() {
        savePath();
        launchExe(currentPath());
    }

    private void runSetup() {
        String setupPath = setupInput.getText().toString().trim();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SETUP, setupPath).apply();
        log("รันตัวติดตั้ง: เลือกที่ติดตั้งเป็นไดรฟ์ F: แล้วกลับมากด \"ค้นหา gta_sa.exe อัตโนมัติ\"");
        launchExe(setupPath);
    }

    /** Returns null when the installer path is usable, otherwise a message. */
    private String checkSetup(String path) {
        File f = new File(path);
        if (!f.isFile()) return "✗ ไม่พบไฟล์: " + path;
        if (!path.startsWith(STORAGE_ROOT + "/")) return "✗ ไฟล์ต้องอยู่ใน " + STORAGE_ROOT;
        if (!path.toLowerCase(Locale.US).endsWith(".exe")) return "✗ ต้องเป็นไฟล์ .exe";
        if (readPeMachine(f) == -1) return "✗ ไม่ใช่ไฟล์ Windows ที่อ่านได้";
        return null;
    }

    private void autoFindSetup() {
        if (!hasStoragePermission()) { log("ยังไม่ได้รับอนุญาตเข้าถึงไฟล์"); return; }
        log("กำลังค้นหา setup.exe ...");
        new Thread(() -> {
            ArrayList<File> found = new ArrayList<>();
            searchName(new File(STORAGE_ROOT), 0, "setup.exe", found);
            runOnUiThread(() -> {
                if (found.isEmpty()) log("ไม่พบ setup.exe");
                else {
                    for (File f : found) log("พบ: " + f.getPath());
                    setupInput.setText(found.get(0).getPath());
                }
                refresh();
            });
        }).start();
    }

    private void launchExe(final String exePath) {
        ContainerManager manager = new ContainerManager(this);
        Container existing = null;
        for (Container c : manager.getContainers()) {
            if (CONTAINER_NAME.equals(c.getName())) { existing = c; break; }
        }
        if (existing != null) {
            startGame(existing, exePath);
            return;
        }

        try {
            JSONObject data = new JSONObject();
            data.put("name", CONTAINER_NAME);
            data.put("screenSize", "1280x720");
            data.put("graphicsDriver", GraphicsDrivers.getDefaultDriver(this));
            data.put("dxwrapper", "dxvk");
            data.put("drives", Container.DEFAULT_DRIVES + "F:" + STORAGE_ROOT);
            launchButton.setEnabled(false);
            log("กำลังสร้าง container สำหรับ GTA SA ...");
            manager.createContainerAsync(data, container -> {
                if (container == null) {
                    log("สร้าง container ไม่สำเร็จ");
                    refresh();
                    return;
                }
                log("สร้าง container เสร็จ (id " + container.id + ")");
                startGame(container, exePath);
            });
        }
        catch (Exception e) {
            log("ผิดพลาด: " + e.getMessage());
        }
    }

    private void startGame(Container container, String exePath) {
        log("เปิด " + new File(exePath).getName() + " ใน container " + container.id);
        Intent intent = new Intent(this, XServerDisplayActivity.class);
        intent.putExtra("container_id", container.id);
        intent.putExtra("exec_path", exePath);
        startActivity(intent);
    }
}
