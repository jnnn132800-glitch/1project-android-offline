# คู่มือการ Build และทดสอบ Phase 01 ใน Android Studio

> **สถานะใน Container ปัจจุบัน**: `BUILD_NOT_RUN` เนื่องจากสภาพแวดล้อม Container ไม่มี JDK, Android SDK, Gradle Distribution และ Android Emulator ตามกฎข้อ 01 จึงไม่สร้าง `gradle-wrapper.jar` ปลอมและไม่อ้างว่ามีไฟล์ `.apk` ที่คอมไพล์แล้วใน Container นี้

---

## 1. สิ่งที่ส่งมอบใน Phase 01
1. **โครงสร้างโปรเจกต์ Android มาตรฐาน**:
   - `/settings.gradle.kts`, `/build.gradle.kts`, `/gradle.properties`, `/gradle/libs.versions.toml`, `/app/build.gradle.kts`, `/app/proguard-rules.pro`
2. **การบังคับใช้ Offline 100% และความปลอดภัยข้อมูลสำรองตั้งแต่ระดับ Manifest**:
   - `/app/src/main/AndroidManifest.xml`: **ไม่มี `android.permission.INTERNET`** และตั้ง `android:allowBackup="false"` พร้อม `/app/src/main/res/xml/backup_rules.xml` และ `data_extraction_rules.xml` เพื่อป้องกันการคัดลอกไฟล์ `.db` ขณะเปิดใช้งานแบบไม่สอดคล้องกับ WAL
3. **ฐานข้อมูล Room เบื้องต้นและ Airplane-Mode First Launch Bootstrap**:
   - `OneProjectDatabase.kt` (บังคับใช้ `JournalMode.WRITE_AHEAD_LOGGING` และ `setForeignKeyConstraintsEnabled(true)`, ไม่มี `fallbackToDestructiveMigration`)
   - `RoundEntity.kt`, `PolicyVersionEntity.kt`, `AuditLogEntity.kt`, `RoundStatus.kt`
   - `BootstrapDao.kt` (`@Transaction bootstrapDemoSeedIfEmpty`), `OfflineBootstrapRepository.kt`, `EnsureFirstLaunchBootstrapUseCase.kt`
4. **Jetpack Compose Navigation 5 หมวด, Theme กรมท่า+Teal และฟอนต์ไทยในเครื่อง**:
   - `Color.kt`, `Type.kt`, `Theme.kt`, `OneProjectDestination.kt`, `MainViewModel.kt`, `OneProjectApp.kt`, `MainActivity.kt`
   - แถบ Header แสดงงวดและสถานะพร้อมข้อความกำกับเสมอ (`[OPEN]`, `[DRAFT]` ฯลฯ) และป้าย **"ข้อมูลในเครื่อง (Single-Device Room WAL) • ไม่ใช้เซิร์ฟเวอร์"**
5. **ชุดทดสอบ Phase 01**:
   - Unit Test: `FirstLaunchBootstrapUnitTest.kt`
   - AndroidX Room Instrumentation Test: `AirplaneModeFirstLaunchRoomTest.kt`

---

## 2. ขั้นตอนการเปิดโปรเจกต์และสร้าง Debug APK ใน Android Studio บนเครื่องพัฒนา

1. **เปิดโปรเจกต์**:
   - เปิด **Android Studio (Ladybug / Koala หรือใหม่กว่า)** ที่ตั้งค่า **JDK 17** และ **Android SDK 35 (Min SDK 26)**
   - เลือก **Open** แล้วชี้มาที่โฟลเดอร์รากของโปรเจกต์ที่มีไฟล์ `settings.gradle.kts`
2. **Sync Gradle / สร้าง Wrapper มาตรฐาน**:
   - ให้ Android Studio ทำการ Sync Project with Gradle Files (หรือรัน `gradle wrapper --gradle-version 8.9` บนเครื่องที่มี Gradle ติดตั้งอยู่เพื่อสร้าง `gradlew` และ `gradle/wrapper/gradle-wrapper.jar` ของแท้)
3. **รัน Unit Test ของ Phase 01**:
   ```bash
   ./gradlew testDebugUnitTest
   ```
4. **สร้าง Debug APK**:
   ```bash
   ./gradlew assembleDebug
   ```
   - ไฟล์ Debug APK ที่สร้างเสร็จจริงจะอยู่ที่:
     `app/build/outputs/apk/debug/app-debug.apk`
5. **ทดสอบ Airplane-Mode First Launch บนเครื่องจริงหรือ Emulator**:
   - เปิด **โหมดเครื่องบิน (Airplane Mode)** และปิด Wi-Fi / Mobile Data บนอุปกรณ์ Android ก่อนเปิดแอป
   - ติดตั้ง `app-debug.apk` และเปิดแอปครั้งแรก (`First Launch`)
   - ตรวจสอบว่าแอปเปิดขึ้นได้ทันทีโดยไม่ถามหาอินเทอร์เน็ต แสดงงวด `DEMO-2026-001` สถานะ `เปิดรับรายการ [OPEN]` รุ่นนโยบาย `#1` บน Header และสลับแท็บทั้ง 5 หมวด (`ภาพรวม`, `รับรายการ`, `บิล`, `รายงาน`, `เพิ่มเติม`) ได้ครบถ้วน
   - ปิดแอป (Force Stop) แล้วเปิดใหม่อีกครั้ง ตรวจสอบว่าข้อมูลเดิมยังอยู่ครบและไม่ถูกรีเซ็ตซ้ำ
