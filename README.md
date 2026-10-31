# SA Runner — ต้นแบบเฟส 3 (ตรวจเครื่อง + ไฟล์เกม)

แอปนี้ **ยังไม่รัน GTA SA** ทำแค่ 4 อย่าง:
1. ตรวจ CPU / Android / หน้าหน่วยความจำ → บอกว่าต้องใช้ Box86 หรือ Box64 + WoW64
2. ตรวจ Vulkan (เวอร์ชัน, ไดรเวอร์, extension ที่ DXVK ต้องการ) → บอกว่าควรลอง DXVK รุ่นไหน
3. ให้เลือกโฟลเดอร์เกม PC ของคุณเอง แล้วตรวจไฟล์แบบอ่านอย่างเดียว (ไม่คัดลอก ไม่รันไฟล์ใดๆ)
4. เก็บ log ให้คัดลอก/แชร์ได้

## สถาปัตยกรรมเป้าหมาย (เฟส 2)

```
[แอป SA Runner: Kotlin launcher + ปุ่มสัมผัส]
        │ เริ่ม process
        ▼
[X server ภายในแอป (จาก Winlator)] ◄── แสดงผลบน SurfaceView ใน Activity
        │
gta_sa.exe (x86 32 บิต)
        │
Wine (WoW64)  ──  Windows API
        │
Box64 (แปลงคำสั่ง x86 → arm64)
        │  D3D9
        ▼
DXVK d3d9.dll  ──►  Vulkan  ──►  Turnip (Adreno) หรือ ไดรเวอร์เครื่องผ่าน Vortek (Mali/อื่นๆ)
```

ส่วน runtime ทั้งหมดจะมาจากการ fork ซอร์ส Winlator (LGPL-2.1) ในเฟส 4 — ต้นแบบนี้ยังไม่มีโค้ดของโปรเจกต์อื่นอยู่เลย

## โครงสร้างไฟล์

```
sa-runner/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── .github/workflows/build.yml          ← build APK บน GitHub
└── app/
    ├── build.gradle.kts                 ← minSdk 26, arm64-v8a เท่านั้น
    └── src/main/
        ├── AndroidManifest.xml
        ├── cpp/
        │   ├── CMakeLists.txt
        │   └── vulkan_probe.cpp         ← ตรวจ Vulkan ผ่าน NDK
        └── java/com/sarunner/launcher/
            ├── MainActivity.kt          ← หน้าจอหลัก + log
            ├── DeviceProbe.kt           ← CPU/ABI/RAM/page size
            ├── VulkanProbe.kt           ← JNI + คำแนะนำเส้นทางกราฟิก
            ├── GameValidator.kt         ← ตรวจไฟล์เกม + อ่าน PE header
            └── RuntimeStatus.kt         ← ตรวจว่ามี runtime หรือยัง
```

## วิธี build จากมือถือ (ไม่ต้องมีคอม)

1. สร้าง repo ใหม่บน github.com (ตั้งเป็น Private ก็ได้)
2. อัปโหลดไฟล์ทั้งหมดในโฟลเดอร์นี้ (Add file → Upload files) ให้โครงสร้างโฟลเดอร์เหมือนด้านบน
   - โฟลเดอร์ `.github` ต้องไปด้วย ถ้าอัปโหลดผ่านเว็บแล้วหาย ให้สร้างไฟล์ `.github/workflows/build.yml` เองแล้ววางเนื้อหาลงไป
3. ไปแท็บ **Actions** → เลือก "Build APK" → **Run workflow**
4. รอจนขึ้นเครื่องหมายถูกสีเขียว (ประมาณ 5–10 นาที) → กดเข้าไป → ดาวน์โหลด **sa-runner-debug-apk**
5. แตก zip แล้วติดตั้ง `app-debug.apk` บนเครื่อง (ต้องอนุญาตติดตั้งจากแหล่งที่ไม่รู้จักให้แอปที่ใช้เปิดไฟล์)

## วิธี build บนคอม

ติดตั้ง Android Studio (มี SDK, NDK, CMake) แล้วเปิดโฟลเดอร์นี้ → Build → Build APK(s)
หรือใช้คำสั่ง (ต้องมี Gradle 8.9 ขึ้นไป และตั้ง `ANDROID_HOME`):

```
gradle :app:assembleDebug
```

ไฟล์ได้ที่ `app/build/outputs/apk/debug/app-debug.apk`

## วิธีทดสอบ

1. เปิดแอป → ดูส่วน "สถานะเครื่อง"
2. กด "เลือกโฟลเดอร์ GTA San Andreas (PC)" → เลือก **โฟลเดอร์หลักของเกม** (ที่มี gta_sa.exe)
3. กด "คัดลอก log" แล้วส่งกลับมาในแชต
   - log บันทึกเฉพาะชื่อโฟลเดอร์ ไม่บันทึก path เต็ม

## ข้อจำกัดที่รู้แล้ว

- ยังไม่รันเกม (runtime จะเพิ่มในเฟส 4)
- ยังไม่ระบุเวอร์ชันเกม (1.0 US / Steam) เพราะยังไม่มีข้อมูลขนาด/แฮชที่ยืนยันแล้ว
- คำแนะนำ DXVK อ้างอิงเงื่อนไขขั้นต่ำจาก DXVK wiki เท่านั้น ไม่ใช่ผลทดสอบจริง
- โค้ด Kotlin ยังไม่ได้คอมไพล์ในสภาพแวดล้อมที่เขียน (ไม่มี Android SDK) — การ build ครั้งแรกบน GitHub คือการตรวจครั้งแรก ถ้าล้มเหลวให้ส่ง error มา

## กฎหมาย

- ไม่มีไฟล์เกมหรือไฟล์ของ Rockstar อยู่ในแอป ผู้ใช้ต้องใช้ไฟล์เกมที่ซื้อมาเอง
- ยังไม่มีโค้ดบุคคลที่สาม เมื่อเพิ่ม Winlator/Wine/Box64/DXVK/Mesa ในเฟส 4 จะเพิ่มไฟล์ไลเซนส์และ notice ตามที่แต่ละโปรเจกต์กำหนด
