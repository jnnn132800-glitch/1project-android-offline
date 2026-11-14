# Phase 06B — Backup & Restore Implementation Specification (1Project Android Offline)

เอกสารฉบับนี้สรุปรายละเอียดการพัฒนาโค้ดจริงของระบบสำรองและกู้คืนข้อมูลแบบยืนยันความถูกต้อง (**Phase 06B**) ต่อเนื่องจากแบบแผนความปลอดภัยที่ได้อนุมัติใน **Phase 06A (`D10`)**

---

## 1. โมดูลและคลาสที่พัฒนาใน Phase 06B

1. **`BackupArchiveCryptoEngine.kt`** (`/app/src/main/java/com/oneproject/offline/core/security/BackupArchiveCryptoEngine.kt`)
   - เข้ารหัสและถอดรหัสไฟล์สำรอง `.1pbackup` ด้วยมาตรฐาน JCA ใน Android SDK (`PBKDF2WithHmacSHA256` 210,000 รอบ, Salt 16 ไบต์ + `AES/GCM/NoPadding` กุญแจ 256 บิต, IV 12 ไบต์, Authentication Tag 128 บิต)
   - ผูกส่วนหัว `AuthenticatedBackupHeader` เข้ากับ `AES-GCM Additional Authenticated Data (AAD)`
   - ล้างข้อมูลกุญแจ (`ByteArray.fill(0)`) ในบล็อก `finally` เสมอ
   - ไม่ผูกกุญแจกับ `Android Keystore` ของเครื่องเก่า เพื่อให้กู้คืนข้ามเครื่องได้จริงเมื่อเครื่องเดิมสูญหายหรือพัง (`D10`)

2. **`OfflineBackupAndRestoreManager.kt`** (`/app/src/main/java/com/oneproject/offline/data/backup/OfflineBackupAndRestoreManager.kt`)
   - **ขาสำรองข้อมูล (`exportConsistentAuthenticatedBackup`)**:
     - บังคับรัน `PRAGMA wal_checkpoint(TRUNCATE)` และตรวจว่า `busy == 0` ก่อนสร้าง Snapshot (ห้ามคัดลอกไฟล์ `.db` ที่เปิดอยู่โดยไม่รวม WAL)
     - ตรวจพื้นที่ว่างในเครื่อง (`availableBytes >= dbSize * 3 + 32 MB`)
     - คัดลอก Snapshot ไปเข้ารหัสเป็น `.1pbackup` พร้อมบันทึก `BackupMetadataEntity` (`EXPORT_AUTHENTICATED_BACKUP`) และ `AuditLogEntity`
   - **ขากู้คืนขั้นตอนที่ 1 (`stageAndValidateArchive`)**:
     - ถอดรหัสไฟล์สำรองลงเฉพาะในไฟล์พัก `backup_staging/restore_candidate_<token>.db`
     - ตรวจ `AES-GCM Tag + AAD`, ตรวจ `SHA-256` เทียบกับส่วนหัว, และเปิดไฟล์ Staging แบบ Read-Only เพื่อรัน `PRAGMA integrity_check`, `PRAGMA foreign_key_check`, `PRAGMA user_version == 2`, และตรวจรายชื่อตารางหลักครบถ้วน
   - **ขากู้คืนขั้นตอนที่ 2 (`executeValidatedReplaceOnlyRestore`)**:
     - บังคับให้ผู้ใช้ยืนยัน `confirmReplaceOnlyOverwrite = true`
     - ปิด `OneProjectDatabase` ปัจจุบัน, สำรองฐานข้อมูลเดิมไว้ที่ `.pre_restore_rollback`, ลบไฟล์ `-wal` / `-shm` เดิม, แล้วสลับไฟล์จาก `Staging` เข้าแทนที่แบบ **Replace-Only (Never Merge)**
     - หากเกิดข้อผิดพลาดระหว่างสลับไฟล์หรือเปิดฐานข้อมูลใหม่ ระบบจะนำไฟล์ `.pre_restore_rollback` กลับคืนอัตโนมัติ

3. **`BackupAndRestoreViewModel.kt` & `BackupAndRestoreScreen.kt`** (`/app/src/main/java/com/oneproject/offline/ui/backup/`)
   - เชื่อมต่อกับ `ActivityResultContracts.CreateDocument` และ `ActivityResultContracts.OpenDocument` (Storage Access Framework — SAF Document Picker)
   - แสดงผลการตรวจความสมบูรณ์ของไฟล์ในพื้นที่ Staging (รหัสไฟล์, SHA-256, `PRAGMA integrity_check`, งวดล่าสุด และจำนวนบิลในไฟล์สำรอง) พร้อมกล่องติ๊กยืนยันก่อนเขียนทับแบบ Replace-Only

---

## 2. คำสั่งรันทดสอบใน Android Studio

```bash
./gradlew testDebugUnitTest --tests "com.oneproject.offline.core.security.Phase06ABackupDesignSpecTest" --tests "com.oneproject.offline.data.backup.Phase06BBackupRestoreTest"
```
