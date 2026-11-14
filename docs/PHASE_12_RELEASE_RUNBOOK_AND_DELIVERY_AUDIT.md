# Phase 12 — คู่มือการ Build, การติดตั้ง, การอัปเดต, การกู้คืนข้อมูล และรายงานตรวจรับการส่งมอบ (Release Runbook & Delivery Audit)

## 1. การแยกสถานะการส่งมอบอย่างชัดเจน (Build vs Installation vs Real-Device Testing)

| หมวดหมู่การตรวจสอบ | สถานะจริงในสภาพแวดล้อมปัจจุบัน | สาเหตุและข้อเท็จจริง |
|---|---|---|
| **1. การสร้างไฟล์ติดตั้ง (APK Build — Debug & Release)** | **`BLOCKED` (`BUILD_NOT_RUN`)** | สภาพแวดล้อม Linux Container ปัจจุบันไม่มี JDK (`java`, `javac`), Kotlin Compiler (`kotlinc`), Gradle Runtime และ Android SDK (`ANDROID_HOME`) จึงไม่สามารถรัน `./gradlew assembleDebug` หรือ `./gradlew assembleRelease` เพื่อผลิตไฟล์ `.apk` จริงใน Container นี้ได้ **ไม่มีการสร้างไฟล์ `.apk` ปลอม หรือ `gradle-wrapper.jar` ปลอม** |
| **2. การติดตั้งลงอุปกรณ์ (APK Installation)** | **`NOT_RUN`** | เนื่องจากไม่มีไฟล์ `.apk` ที่คอมไพล์จาก Android SDK ใน Container และไม่มีอุปกรณ์ Android เชื่อมต่อผ่าน `adb devices` |
| **3. การทดสอบบนเครื่องจริง (Real-Device Testing)** | **`NOT_RUN`** | ต้องดำเนินการบนโทรศัพท์หรือแท็บเล็ต Android จริง (Android 8.0 / API 26 ขึ้นไป) หลัง Build ผ่าน Android Studio ในเครื่องของผู้พัฒนา/เจ้าของงาน |

---

## 2. การแยกประเภท Build: `Debug` vs `Owner-Signed Release` (`app/build.gradle.kts`)

1. **รุ่นทดสอบ (`Debug Build`)**:
   - `applicationId`: `com.oneproject.offline.debug`
   - `versionName`: `1.0.0-phase12-DEBUG`
   - สามารถติดตั้งคู่กับรุ่นใช้งานจริงบนเครื่องเดียวกันได้โดยไม่ทับฐานข้อมูลกัน
2. **รุ่นลงนามโดยเจ้าของงาน (`Owner-Signed Release Build`)**:
   - `applicationId`: `com.oneproject.offline`
   - `versionName`: `1.0.0-phase12` (`versionCode = 12`)
   - เปิดใช้ `isMinifyEnabled = true` และ `isShrinkResources = true` พร้อมกฎรักษาคลาส Room/SQLite ใน `app/proguard-rules.pro`
   - อ่านค่าลงนามจากไฟล์ `keystore.properties` ในเครื่องเจ้าของงานเท่านั้น (ไม่อยู่ใน Git และห้ามใช้กุญแจ Debug มาปลอมเป็น Release)

### ตัวอย่างไฟล์ `keystore.properties` (สร้างเฉพาะในเครื่องเจ้าของงาน ห้าม Commit ขึ้น Git)
```properties
storeFile=/absolute/path/to/owner-release-key.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=oneproject_owner_key
keyPassword=YOUR_KEY_PASSWORD
```

---

## 3. ขั้นตอนการ Build, ติดตั้ง, อัปเดตข้ามเวอร์ชัน และกู้คืนข้อมูลใน Android Studio

### 3.1 การเปิดโปรเจกต์และรัน Unit Tests (Phase 00–12)
1. เปิด **Android Studio (Ladybug / Koala หรือใหม่กว่า)** ที่ตั้งค่า **JDK 17** และ **Android SDK 35**
2. เลือก **File -> Open** ชี้ไปที่โฟลเดอร์โปรเจกต์ แล้วกด **Sync Project with Gradle Files**
3. รันชุดทดสอบ Unit Test ทั้งหมด (Phase 00–11):
   ```bash
   ./gradlew testDebugUnitTest
   ```

### 3.2 การสร้างไฟล์ Debug APK และ Owner-Signed Release APK
1. **สร้าง Debug APK**:
   ```bash
   ./gradlew assembleDebug
   ```
   ไฟล์ผลลัพธ์จะอยู่ที่: `app/build/outputs/apk/debug/app-debug.apk`
2. **สร้าง Owner-Signed Release APK** (เมื่อวาง `keystore.properties` ในเครื่องเจ้าของแล้ว):
   ```bash
   ./gradlew assembleRelease
   ```
   ไฟล์ผลลัพธ์จะอยู่ที่: `app/build/outputs/apk/release/app-release.apk`

### 3.3 ขั้นตอนการติดตั้งและอัปเดตเวอร์ชันโดยข้อมูลไม่สูญหาย (In-Place Signed Update)
1. **ก่อนอัปเดตเวอร์ชันทุกครั้ง**:
   - เปิดแอป ไปที่แท็บ **เพิ่มเติม (More) -> สำรองและกู้คืนข้อมูลออฟไลน์ (Phase 06B)**
   - ตั้งรหัสผ่านกู้คืน (Portable Recovery Passphrase) และส่งออกไฟล์ `.1pbackup` เก็บไว้ในโฟลเดอร์ปลอดภัยผ่าน SAF
2. **การติดตั้งทับเพื่ออัปเดต (Upgrade)**:
   - ต้องลงนามไฟล์ Release APK รุ่นใหม่ด้วย **Keystore เดียวกับรุ่นเดิม**
   - รันคำสั่งอัปเดตโดยไม่ลบข้อมูล (`-r`):
     ```bash
     adb install -r app/build/outputs/apk/release/app-release.apk
     ```
   - ฐานข้อมูล `OneProjectDatabase` ตั้งค่าโดย **ไม่มี `fallbackToDestructiveMigration()`** เพื่อป้องกันการล้างฐานข้อมูลเงียบๆ
3. **การกู้คืนข้อมูลข้ามเครื่องเมื่อเครื่องเดิมสูญหายหรือพัง (Disaster Recovery)**:
   - ติดตั้งแอปลงบนเครื่องใหม่
   - เปิดแท็บ **เพิ่มเติม (More) -> สำรองและกู้คืนข้อมูลออฟไลน์ (Phase 06B)**
   - เลือกไฟล์ `.1pbackup` และกรอกรหัสผ่านกู้คืน (Portable Recovery Passphrase)
   - ระบบจะถอดรหัส `PBKDF2WithHmacSHA256 + AES/GCM/NoPadding`, ตรวจสอบค่า `SHA-256` และ `PRAGMA integrity_check` ในพื้นที่พัก (Staging) ก่อนสลับฐานข้อมูลแบบ Replace-Only

---

## 4. รายการที่ยังไม่อนุญาตให้ใช้กับข้อมูลเงินจริงเชิงพาณิชย์ (Production Blockers)

1. **`D05` (เพดานความเสี่ยงและตารางอัตราจ่ายจริง)**: ต้องได้รับการยืนยันตัวเลขจริงจากเจ้าของงาน
2. **`D08` (ผังบัญชีจริงและสิทธิ์อนุมัติรายการปรับปรุงงวดที่ปิดแล้ว)**: ปัจจุบันใช้ผังบัญชีสาธิต (`isDemoChart = true`)
3. **`D09` (การเชื่อมต่อระบบโอนเงินภายนอก)**: ปิดกั้น 100% ตามการออกแบบ (`isExternalExecutionDisabled = true`)
4. **`D10` (การเข้ารหัสฐานข้อมูลขณะพักข้อมูล `Database-at-Rest`)**: Room ไม่ได้เข้ารหัสไฟล์ `.db` อัตโนมัติ (เฉพาะไฟล์สำรอง `.1pbackup` ที่เข้ารหัส `AES-256-GCM`)
5. **`D11` (การตรวจทานด้านกฎหมาย คอมพลายแอนซ์ และภาษี)**: ต้องได้รับการลงนามอนุมัติจากผู้เชี่ยวชาญก่อนใช้งานจริง
