# วิธีรับไฟล์ `.apk` (Native Kotlin + Jetpack Compose + Room) ผ่าน GitHub Actions ฟรี (ไม่ต้องลง Android Studio ในเครื่อง)

เนื่องจากสภาพแวดล้อมเซิร์ฟเวอร์ AI Studio ปัจจุบันไม่มีคอมไพเลอร์ Java/Android SDK (`BLOCKED: NO_JAVA / NO_ANDROID_SDK`) และตามกฎเหล็กของโครงการ **ห้ามใช้ Web Wrapper (PWABuilder)** และ **ห้ามสร้างไฟล์ `.apk` ปลอม** คุณจึงสามารถใช้ **GitHub Actions** คอมไพล์ซอร์สโค้ดจริงทั้งหมด (`Phase 00–12`) ให้เป็นไฟล์ `app-debug.apk` ของแท้ได้ภายใน 3 ขั้นตอนดังนี้:

---

## ขั้นตอนที่ 1: ดาวน์โหลดไฟล์ซอร์สโค้ด `.zip` จากหน้าเว็บพรีวิวนี้
1. กดปุ่ม **"ดาวน์โหลดซอร์สโค้ด Android + GitHub Actions (.zip)"** บนหน้าเว็บพรีวิว (หรือดาวน์โหลดจากพาธ `/downloads/1Project-Android-Offline-Phase00-12-Source.zip`)
2. แตกไฟล์ `.zip` ออกมา จะพบโฟลเดอร์และไฟล์ครบทั้งหมด ได้แก่:
   - `.github/workflows/build-android-apk.yml` (สคริปต์สั่งให้ GitHub ติดตั้ง JDK 17 + Android SDK 35 + Gradle 8.9 ของแท้ แล้วรัน Unit Tests และสร้างไฟล์ `app-debug.apk`)
   - `app/` (ซอร์สโค้ด Kotlin + Jetpack Compose + Room SQLite WAL ครบ Phase 01–12)
   - `gradle/`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `docs/`

---

## ขั้นตอนที่ 2: อัปโหลดขึ้น GitHub Repository ของคุณ
1. ไปที่ [https://github.com/new](https://github.com/new) แล้วสร้าง Repository ใหม่ (แนะนำให้ตั้งเป็น **Private** เพื่อความเป็นส่วนตัว)
2. อัปโหลดไฟล์ทั้งหมดที่แตกออกมาจาก `.zip` ขึ้นสู่กิ่ง `main` (ผ่าน Git CLI หรือลากวางผ่านหน้าเว็บ GitHub `uploading an existing file`)
   - *ข้อสำคัญ*: ตรวจสอบให้แน่ใจว่าโฟลเดอร์ `.github/workflows/build-android-apk.yml` ถูกอัปโหลดไปด้วย

---

## ขั้นตอนที่ 3: ดาวน์โหลดไฟล์ `app-debug.apk` จากแท็บ Actions
1. กดที่แท็บ **Actions** ด้านบนของหน้า GitHub Repository ของคุณ
2. จะเห็นงานชื่อ **"Build 1Project Native Android APK (Debug & Unit Tests)"** กำลังทำงาน (หรือกดปุ่ม **Run workflow** ได้ทันที)
3. รอประมาณ 2–4 นาที จนขึ้นเครื่องหมายถูกสีเขียว ✅
4. เลื่อนลงมาที่หัวข้อ **Artifacts** ด้านล่างสุดของหน้านั้น แล้วกดดาวน์โหลด:
   - **`1Project-Android-Offline-Debug-APK`** (ข้างในคือไฟล์ `app-debug.apk` ของแท้ที่คอมไพล์จากโค้ด Native Kotlin + Room)
   - **`1Project-JUnit-Test-Reports`** (รายงานผลการรันชุดทดสอบ Unit Test ของ Phase 01–11)
5. นำไฟล์ `app-debug.apk` ไปติดตั้งลงบนมือถือ Android (Android 8.0 / API 26 ขึ้นไป) เพื่อทดสอบในโหมดเครื่องบิน (Airplane Mode) ได้ทันที
