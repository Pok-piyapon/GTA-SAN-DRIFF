#!/usr/bin/env python3
"""Apply the SA Runner overlay to a clean checkout of brunodev85/winlator-app.

Usage: python3 runtime/apply_overlay.py <path-to-winlator-app-checkout>

Every edit is checked: if upstream changed and a pattern is not found, the script
stops with an error instead of producing a half-patched build.
"""
import pathlib
import re
import shutil
import sys

HERE = pathlib.Path(__file__).resolve().parent


def die(msg):
    print("apply_overlay: " + msg, file=sys.stderr)
    sys.exit(1)


def replace_once(path, old, new):
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        die(f"expected exactly one match in {path}: {old!r} (found {text.count(old)})")
    path.write_text(text.replace(old, new), encoding="utf-8")
    print(f"patched {path.relative_to(ROOT)}")


if len(sys.argv) != 2:
    die("usage: apply_overlay.py <winlator-app checkout>")
ROOT = pathlib.Path(sys.argv[1]).resolve()
MAIN = ROOT / "app/src/main"
if not (MAIN / "AndroidManifest.xml").is_file():
    die(f"{ROOT} does not look like a winlator-app checkout")

# 1. Our launcher activity
dst = MAIN / "java/com/winlator/SARunnerActivity.java"
shutil.copyfile(HERE / "src/SARunnerActivity.java", dst)
print(f"copied {dst.relative_to(ROOT)}")

shutil.copyfile(HERE / "src/SARunLog.java", MAIN / "java/com/winlator/SARunLog.java")
print("copied app/src/main/java/com/winlator/SARunLog.java")

# 2. Manifest: Winlator's MainActivity stops being the launcher; SARunnerActivity becomes it.
manifest = MAIN / "AndroidManifest.xml"
text = manifest.read_text(encoding="utf-8")
launcher = re.compile(
    r'(<activity android:name="com\.winlator\.MainActivity".*?>)\s*'
    r'<intent-filter>\s*<action android:name="android\.intent\.action\.MAIN"\s*/>\s*'
    r'<category android:name="android\.intent\.category\.LAUNCHER"\s*/>\s*</intent-filter>',
    re.S,
)
text, n = launcher.subn(r"\1", text, count=1)
if n != 1:
    die("could not remove the LAUNCHER intent-filter from MainActivity")
sa_activity = """
        <activity android:name="com.winlator.SARunnerActivity"
            android:theme="@style/AppThemeDark"
            android:exported="true"
            android:screenOrientation="sensor"
            android:configChanges="keyboard|keyboardHidden|orientation|screenSize|screenLayout|smallestScreenSize|density|navigation">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
"""
text, n = re.subn(r'(\s*<activity android:name="com\.winlator\.MainActivity")', sa_activity + r"\1", text, count=1)
if n != 1:
    die("could not insert SARunnerActivity into the manifest")
manifest.write_text(text, encoding="utf-8")
print("patched app/src/main/AndroidManifest.xml")

# 3. App name
for strings in (MAIN / "res").glob("values*/strings.xml"):
    s = strings.read_text(encoding="utf-8")
    s2, n = re.subn(r'(<string name="app_name">)[^<]*(</string>)', r"\1SA Runner\2", s)
    if n:
        strings.write_text(s2, encoding="utf-8")
        print(f"patched {strings.relative_to(ROOT)}")

# 4. Let the installer run from any AppCompatActivity, not only Winlator's MainActivity.
installer = MAIN / "java/com/winlator/xenvironment/RootFSInstaller.java"
replace_once(installer, "public static void install(final MainActivity activity)",
             "public static void install(final AppCompatActivity activity)")
replace_once(installer, "public static void installIfNeeded(final MainActivity activity)",
             "public static void installIfNeeded(final AppCompatActivity activity)")

# 5. Record Wine/Box64 output of each run to a file (SARunLog).
replace_once(MAIN / "java/com/winlator/XServerDisplayActivity.java",
             "ProcessHelper.removeAllDebugCallbacks();",
             "ProcessHelper.removeAllDebugCallbacks();\n        SARunLog.attach(this);")

print("overlay applied")
