# SA Runner runtime (เฟส 4)

แอปตัวนี้คือ **Winlator 11.2 (ซอร์สจาก brunodev85/winlator-app)** ที่เพิ่มหน้าเปิดเกม GTA San Andreas เข้าไป
ตัว runtime (Wine, Box64, DXVK 1.10.3 / 2.4.1, Vortek, Turnip, X server) มาจาก Winlator ทั้งหมด ไม่ได้แก้ไข

## GitHub Actions ทำอะไร

1. ดึงซอร์ส winlator-app ที่ commit `8077f374ef2d54127b088de9e8cd7b4af14d9c8b`
2. รัน `apply_overlay.py`:
   - เพิ่ม `SARunnerActivity.java` เป็นหน้าแรกของแอป
   - ให้หน้า Winlator เดิมเปิดได้จากปุ่ม "ตั้งค่าขั้นสูง"
   - เปลี่ยนชื่อแอปเป็น SA Runner
   - ให้ตัวติดตั้งไฟล์ระบบเรียกจากหน้าใหม่ได้
3. เซ็นด้วยคีย์ใน `debug.keystore.b64` (คีย์ทดสอบ ไม่ใช่คีย์สำหรับเผยแพร่) เพื่อให้ติดตั้งทับรุ่นเดิมได้
4. Build ด้วย Gradle wrapper ของ Winlator

## การตั้งค่าที่หน้าเปิดเกมใช้

| รายการ | ค่า |
|---|---|
| ไดรเวอร์ Vulkan | Turnip ถ้าเป็น Adreno, ไม่งั้น Vortek (ตาม Winlator) |
| DX wrapper | DXVK — Winlator เลือก 1.10.3 เองเมื่อใช้ Vortek กับ Vulkan ต่ำกว่า 1.3 |
| ความละเอียด | 1280x720 |
| ไดรฟ์ | D: = Download, E: = พื้นที่ของแอป, F: = /storage/emulated/0 |

เปลี่ยนค่าได้ทาง "ตั้งค่าขั้นสูง (Winlator)" → Containers → "GTA San Andreas (SA Runner)"

## ข้อจำกัดที่รู้แล้ว

- **ชื่อแพ็กเกจยังเป็น `com.winlator`** เพราะโค้ด native และไลบรารีที่ build ไว้แล้ว (Vortek, Gladio) ฝัง path `/data/data/com.winlator/...` ไว้ ถ้าเครื่องมี Winlator อยู่แล้วต้องลบก่อน การเปลี่ยนชื่อแพ็กเกจต้อง build ส่วนเหล่านี้ใหม่ (งานในเฟส 7)
- ปุ่มสัมผัสยังใช้ระบบของ Winlator (เปิดเมนูในเกมด้วยปุ่ม Back → Input Controls) ยังไม่มีชุดปุ่มสำหรับ GTA SA โดยเฉพาะ
- ยังไม่ได้ทดสอบว่า GTA SA เข้าเกมได้ เป็นสิ่งที่ต้องทดสอบในเฟส 5

## ไลเซนส์

Winlator และส่วนประกอบใช้ LGPL-2.1 และไลเซนส์ของแต่ละโปรเจกต์ (Wine LGPL, Box64 MIT, DXVK zlib, Mesa MIT)
ซอร์สทั้งหมดที่ใช้ build หาได้จาก repo นี้รวมกับ commit ของ winlator-app ที่ระบุไว้ด้านบน
ไม่มีไฟล์เกมหรือไฟล์ของ Rockstar อยู่ในแอป
