# เอกสารออกแบบสถาปัตยกรรมความปลอดภัยและการสำรองข้อมูล (Phase 06A Design Review)

> **วัตถุประสงค์ของ Phase 06A**: ออกแบบและทบทวน Threat Model, การแลกเปลี่ยนข้อดี-ข้อเสียระหว่าง Password / Recovery Key / Android Keystore, การเลือกไลบรารีเข้ารหัสที่ผ่านการรับรอง (Vetted Crypto), และขั้นตอนการทำ WAL-Consistent Snapshot + Crash-Safe Staging Restore เพื่อขออนุมัติก่อนเริ่มเขียนโค้ด Export/Import จริงใน **Phase 06B**

---

## 1. แบบจำลองภัยคุกคามและขอบเขตความรับผิดชอบ (Backup & Local Security Threat Model)

### 1.1 สิ่งที่ระบบปกป้อง (Protected Assets & Threats Addressed)
1. **การคัดลอกไฟล์ `.db` ขณะเปิดใช้งานในโหมด WAL (Inconsistent Open-DB Copy)**:
   - *ความเสี่ยง*: Room เปิดใช้งานโหมด `WRITE_AHEAD_LOGGING` (`WAL`) ข้อมูลที่เพิ่ง Commit อาจยังอยู่ในไฟล์ `-wal` และยังไม่ถูกเขียนลงไฟล์ `.db` หลัก หากคัดลอกเฉพาะไฟล์ `.db` ไปโดยไม่ทำ Checkpoint จะได้ข้อมูลที่ไม่ครบหรือไฟล์ฐานข้อมูลเสียหาย
   - *การป้องกัน*: บังคับทำ **WAL Checkpoint (`PRAGMA wal_checkpoint(TRUNCATE)`)** ภายใต้ Local Backup Lock จนกว่า `busy == 0` และตรวจสอบ `PRAGMA integrity_check` ก่อนสร้างชุดข้อมูลสำรองเสมอ
2. **การแก้ไขปลอมแปลงไฟล์สำรองหรือไฟล์สำรองเสียหายบางส่วน (Tampering & Bit-Rot / Partial Write)**:
   - *ความเสี่ยง*: ไฟล์สำรองที่เก็บใน USB / SD Card / โฟลเดอร์ภายนอกถูกแก้ไขตัวเลขยอดเงิน หรือคัดลอกมาไม่ครบไบต์
   - *การป้องกัน*: ใช้การเข้ารหัสแบบยืนยันความถูกต้องในตัว (**Authenticated Encryption with Associated Data — AEAD: `AES-256-GCM`**) ร่วมกับ `AAD` (ผูก Header, รุ่นไฟล์, รุ่น Schema) และตรวจ `SHA-256` ของไฟล์ฐานข้อมูลหลังถอดรหัสในพื้นที่ Staging ก่อนอนุญาตให้กู้คืน
3. **การกู้คืนทับฐานข้อมูลเดิมด้วยไฟล์เสียหรือรหัสผ่านผิด (Destructive Failed Restore / Mid-Restore Crash)**:
   - *ความเสี่ยง*: หากถอดรหัสทับไฟล์ `.db` ที่ใช้งานอยู่โดยตรง เมื่อใส่รหัสผ่านผิด ไฟล์เสีย หรือแบตเตอรี่หมดกลางคัน ข้อมูลเดิมในเครื่องจะพังทั้งหมด
   - *การป้องกัน*: ใช้กระบวนการ **Staging Validation + Pre-Restore Rollback Path + Replace-Only**:
     - ถอดรหัสและตรวจความถูกต้องทุกขั้นตอนในโฟลเดอร์แยก (`cacheDir/backup_staging/`)
     - หากใส่รหัสผ่านผิด พื้นที่เก็บข้อมูลไม่พอ หรือไฟล์ไม่ผ่าน `integrity_check` จะยกเลิกทันทีโดย **ไม่แตะต้องฐานข้อมูลหลักแม้แต่ไบต์เดียว**
     - ก่อนสลับไฟล์จริง จะย้ายไฟล์เดิม (`.db`, `-wal`, `-shm`) ไปยัง `pre_restore_rollback/` เพื่อกู้คืนอัตโนมัติหากเกิด Crash ระหว่างสลับไฟล์

### 1.2 ขอบเขตที่ระบบไม่กล่าวอ้างเกินจริง (Explicit Non-Claims ตามกฎข้อ 01)
1. **App Lock และ Local Roles ไม่ใช่การพิสูจน์ตัวตนระยะไกล (Not Remote Identity Proof)**:
   - ใช้เพื่อป้องกันการกดผิดหรือการเข้าถึงโดยไม่ได้ตั้งใจบนหน้าจอเครื่องเท่านั้น
2. **ไม่สัญญาเรื่อง Root-Proof Audit หรือการแยกบุคคลอิสระบนเครื่องที่ถูก Root**:
   - บนอุปกรณ์ Android เดียวกัน หากผู้ถือเครื่องมีสิทธิ์ Root ย่อมเข้าถึงหน่วยความจำของแอปได้ ระบบจึงไม่กล่าวอ้างว่าเป็น Root-Proof
3. **Room SQLite ในเครื่องไม่ได้เข้ารหัสตลอดเวลาโดยอัตโนมัติ**:
   - ข้อมูลขณะรันในเครื่องพึ่งพาการเข้ารหัสระดับไฟล์ของระบบปฏิบัติการ Android (File-Based Encryption — FBE) ส่วนไฟล์สำรองข้อมูลที่ส่งออกนอกแอป (`Exported Archive`) จะถูกเข้ารหัสด้วย `AES-256-GCM` เสมอ
4. **ไม่บันทึก PIN, รหัสผ่านสำรองข้อมูล หรือกุญแจเข้ารหัสลงใน Logcat หรือ `audit_logs` โดยเด็ดขาด**

---

## 2. การเปรียบเทียบแนวทางกุญแจกู้คืนข้อมูลแบบพกพาได้ (Portable Recovery vs. Password vs. Android Keystore Tradeoffs — `D10`)

### 2.1 เหตุผลที่ห้ามผูกไฟล์สำรองไว้กับ Android Keystore ของเครื่องเดิมเพียงอย่างเดียว
- กุญแจใน **Android Keystore (`Hardware-backed Keystore / StrongBox`)** ถูกออกแบบให้ผูกติดกับฮาร์ดแวร์ของเครื่องเดิมและ **ไม่สามารถส่งออก (Non-exportable)** ข้ามเครื่องได้
- หากเครื่องเดิมสูญหาย จอแตก เปิดไม่ติด หรือต้องการย้ายข้อมูลไปเครื่องใหม่ (**Cross-Device Restore** ซึ่งเป็นเหตุผลหลักของการสำรองข้อมูล) ไฟล์สำรองที่เข้ารหัสด้วย Keystore ของเครื่องเก่าจะ **ไม่สามารถถอดรหัสบนเครื่องใหม่ได้เลย**
- ดังนั้น **Android Keystore** จึงเหมาะสำหรับใช้ป้องกันสถานะในเครื่องเดียวกัน (เช่น Local App Lock token) แต่ **ไฟล์สำรองข้อมูล (`Portable Backup Archive`) ต้องใช้กุญแจที่สร้างจากข้อมูลลับที่เจ้าของพกพาข้ามเครื่องได้**

### 2.2 ตารางเปรียบเทียบทางเลือกการเข้ารหัสไฟล์สำรองแบบพกพาได้ (เสนอเพื่อขออนุมัติใน `D10`)

| ทางเลือก | กลไกทางเทคนิค | กรณีเครื่องเก่าพัง/หาย (Device Loss) | กรณีลืมรหัสผ่าน (Forgotten Secret) | ความเหมาะสมกับ 1Project Offline |
|---|---|---|---|---|
| **ทางเลือก 1 (เสนอเป็นค่ามาตรฐานสำหรับ Phase 06B)**: **User Recovery Passphrase + Printable Recovery Code** | ผู้ใช้ตั้งรหัสผ่านสำรองข้อมูล (อย่างน้อย 10 ตัวอักษร) แปลงเป็นกุญแจ 256-bit ด้วย `PBKDF2WithHmacSHA256` (210,000 รอบ + Salt สุ่ม 16 ไบต์) เข้ารหัสด้วย `AES-256-GCM` | **กู้คืนบนเครื่องใหม่ได้ 100%** โดยไม่ต้องใช้เครื่องเก่า | หากลืมรหัสผ่านและทำใบจดรหัสหาย จะไม่สามารถถอดรหัสไฟล์สำรองได้ (ต้องแจ้งคำเตือนให้ผู้ใช้จดเก็บไว้ก่อน Export) | **แนะนำสูงสุด**: ใช้ไลบรารีมาตรฐานของ Android/JDK ได้ทันที ไม่ต้องพึ่งเซิร์ฟเวอร์ |
| **ทางเลือก 2**: **System-Generated High-Entropy Recovery Key (256-bit Hex/Base32)** | แอปสุ่มรหัสกู้คืน 32 ตัวอักษรให้ผู้ใช้นำไปจด/พิมพ์เก็บไว้ในสมุดเซฟ | **กู้คืนบนเครื่องใหม่ได้ 100%** หากมีใบรหัสกู้คืน | ป้องกันปัญหาผู้ใช้ตั้งรหัสผ่านเดาง่าย แต่ผู้ใช้ต้องเก็บรักษาใบรหัส 32 ตัวอักษรให้ดี | รองรับควบคู่กับทางเลือก 1 ได้ (ให้ปุ่มสร้าง Recovery Passphrase ความปลอดภัยสูงอัตโนมัติ) |
| **ทางเลือก 3**: **Android Keystore เท่านั้น (ไม่ใช้รหัสผ่านพกพา)** | เข้ารหัสด้วยกุญแจในชิปเครื่องเดิม | **กู้คืนข้ามเครื่องไม่ได้เลย (BLOCKED ตามกฎ 01 และ 08)** | ไม่ต้องจำรหัสผ่าน แต่เมื่อเครื่องพังข้อมูลสูญหายถาวร | **ห้ามใช้เป็นวิธีหลักของไฟล์สำรอง** |

---

## 3. ข้อกำหนดอัลกอริทึมและไลบรารีที่ผ่านการรับรอง (Vetted Cryptographic Specification)

ตามกฎข้อ 01 (*"No plaintext PIN, ad-hoc crypto or secrets in logs... Use vetted compatible libraries only after design review"*):
- **ไม่เขียนอัลกอริทึมเข้ารหัสเอง (Zero Ad-Hoc Crypto)** และไม่เพิ่มไลบรารีภายนอกที่ยังไม่ผ่านการตรวจสอบ
- ใช้ **Standard Java Cryptography Architecture (JCA) บน Android API 26+** ซึ่งผ่านการรับรองระดับแพลตฟอร์ม (`Conscrypt / BoringSSL` ภายใน Android Runtime):
  1. **การแปลงรหัสผ่านเป็นกุญแจ (Key Derivation Function — KDF)**:
     - อัลกอริทึม: `PBKDF2WithHmacSHA256` (`javax.crypto.SecretKeyFactory`)
     - จำนวนรอบ (Iterations): `210,000` รอบ (ตามมาตรฐาน OWASP สำหรับ PBKDF2-HMAC-SHA256)
     - Salt: สุ่มใหม่ทุกครั้งที่สำรองข้อมูล ขนาด `16 bytes` (`128 bits`) ด้วย `java.security.SecureRandom`
     - ความยาวกุญแจที่ได้: `256 bits` (`32 bytes`)
  2. **การเข้ารหัสและยืนยันความถูกต้อง (Authenticated Encryption — AEAD)**:
     - อัลกอริทึม: `AES/GCM/NoPadding` (`javax.crypto.Cipher`)
     - Initialization Vector (IV / Nonce): สุ่มใหม่ทุกครั้ง ขนาด `12 bytes` (`96 bits`) ด้วย `SecureRandom`
     - Authentication Tag: `128 bits` (`16 bytes`)
     - **Associated Authenticated Data (`AAD`)**: ผูกข้อมูล Header ของไฟล์สำรอง (`magic = "1PROJ_BK"`, `archiveFormatVersion = 1`, `schemaVersion = 2`, `createdAtEpochMs`, `dbSha256Hex`) เข้ากับ `cipher.updateAAD(...)` หากผู้ไม่หวังดีแก้ไขตัวเลขรุ่น Schema หรือ Header แม้แต่บิตเดียว การถอดรหัส `AES-GCM` จะล้มเหลวด้วย `AEADBadTagException` ทันที
  3. **การจัดเก็บ PIN สำหรับ Local App Lock (เตรียมสำหรับ Phase 11)**:
     - ใช้ `PBKDF2WithHmacSHA256` (`210,000` รอบ + Salt สุ่ม `16 bytes`) และเปรียบเทียบด้วย `MessageDigest.isEqual` แบบ Constant-Time ห้ามเก็บ Plaintext PIN เด็ดขาด

---

## 4. โครงสร้างไฟล์สำรองข้อมูล (`.1projbk`) และขั้นตอน WAL-Consistent Snapshot

### 4.1 ขั้นตอนการสร้าง Consistent Snapshot จากฐานข้อมูล Room ที่เปิดโหมด WAL (Export Protocol)
ห้ามคัดลอกไฟล์ `oneproject_authoritative_offline.db` ที่กำลังเปิดใช้งานโดยตรง แต่ต้องทำตามลำดับดังนี้:
1. **ตรวจสอบพื้นที่ว่างก่อนสำรองข้อมูล (Pre-Export Storage Check)**:
   - ตรวจ `usableSpace` ในโฟลเดอร์ชั่วคราว ต้องมีพื้นที่ว่างอย่างน้อย `3 × ขนาดไฟล์ (.db + -wal)`
2. **สั่งทำ WAL Checkpoint แบบสมบูรณ์ (`WAL Flush & Truncate`)**:
   - รันคำสั่ง SQLite `PRAGMA wal_checkpoint(TRUNCATE)` ผ่าน `SupportSQLiteDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)")`
   - ตรวจสอบค่าผลลัพธ์ 3 ช่อง (`busy`, `log`, `checkpointed`):
     - ต้องได้ `busy == 0` (ไม่มีธุรกรรมค้างที่บล็อกการรวม WAL)
     - หาก `busy != 0` จะปฏิเสธการสร้างไฟล์สำรองพร้อมแจ้งให้ผู้ใช้รอให้ธุรกรรมเสร็จสิ้นก่อน ห้ามสร้างไฟล์สำรองที่ไม่สอดคล้องกัน
3. **คัดลอกไปยังไฟล์ Snapshot ชั่วคราวและตรวจความสมบูรณ์ (`Staging Snapshot Verification`)**:
   - คัดลอกไฟล์ฐานข้อมูลที่ผ่านการ Checkpoint แล้วไปยัง `cacheDir/backup_export_staging/snapshot.db`
   - เปิด `snapshot.db` แบบ Read-Only เพื่อตรวจ:
     - `PRAGMA integrity_check` ต้องได้ `"ok"`
     - `PRAGMA foreign_key_check` ต้องไม่มีแถวที่ละเมิด Foreign Key
     - `PRAGMA user_version` ต้องตรงกับ `OneProjectDatabase` (`version = 2`)
4. **คำนวณ `SHA-256` และเข้ารหัสเป็นไฟล์ `.1projbk`**:
   - คำนวณ `dbSha256Hex` ของ `snapshot.db`
   - สร้าง Header Metadata + เข้ารหัสเนื้อหา `snapshot.db` ด้วย `AES-256-GCM` พร้อม `AAD`
   - บันทึกประวัติลงตาราง `backup_metadata` และ `audit_logs`
   - ลบไฟล์ชั่วคราวใน `cacheDir/backup_export_staging/` ทันที

### 4.2 ขั้นตอนการกู้คืนแบบ Staging Validation & Replace-Only (Restore Protocol)
ตามกฎข้อ 01 (*"Restore in staging, validate before overwrite, preserve recovery path, replace only not merge"*):
1. **ตรวจสอบพื้นที่ว่าง (`Storage Low Guard`)**:
   - ตรวจพื้นที่ว่างในเครื่องก่อนถอดรหัส หากพื้นที่ว่างไม่เพียงพอสำหรับ Staging + Rollback จะยกเลิกทันที (`INSUFFICIENT_STORAGE_SPACE`) โดยไม่แตะต้องฐานข้อมูลเดิม
2. **ตรวจสอบ Header และถอดรหัสในโฟลเดอร์ Staging (`Staging Authentication & Decryption`)**:
   - อ่าน Header ของไฟล์สำรอง ตรวจ `magic == "1PROJ_BK"`, `archiveFormatVersion == 1`, และ `schemaVersion == 2`
   - แปลงรหัสผ่านที่ผู้ใช้ป้อนด้วย `PBKDF2WithHmacSHA256` และถอดรหัส `AES-256-GCM` ลงใน `cacheDir/backup_restore_staging/staged_restore.db`
   - **หากรหัสผ่านผิด หรือไฟล์ถูกแก้ไขแม้แต่ 1 บิต**: `AES-GCM` จะโยน `AEADBadTagException` ระบบจะลบไฟล์ใน `backup_restore_staging` และแจ้งเตือน `WRONG_PASSWORD_OR_CORRUPT_ARCHIVE` ทันที **โดยที่ฐานข้อมูลเดิมยังทำงานได้ตามปกติ 100%**
3. **ตรวจสอบความสมบูรณ์ของฐานข้อมูลใน Staging ก่อนสลับไฟล์ (`Pre-Overwrite Validation`)**:
   - คำนวณ `SHA-256` ของ `staged_restore.db` เทียบกับ `dbSha256Hex` ใน Header ที่ผ่านการยืนยันตัวตนแล้ว
   - เปิด `staged_restore.db` เพื่อรัน `PRAGMA integrity_check`, `PRAGMA foreign_key_check` และตรวจ `schemaVersion == 2`
4. **สลับไฟล์แบบ Replace-Only พร้อมเก็บรักษาทางหนีทีไล่ (`Atomic Swap with Pre-Restore Rollback Path`)**:
   - ปิดการเชื่อมต่อ `OneProjectDatabase` ปัจจุบัน
   - ย้ายไฟล์ปัจจุบัน (`oneproject_authoritative_offline.db`, `-wal`, `-shm`) ไปเก็บไว้ในโฟลเดอร์สำรองฉุกเฉิน `filesDir/pre_restore_safety/`
   - คัดลอก/ย้าย `staged_restore.db` เข้ามาแทนที่ `oneproject_authoritative_offline.db` (แบบ **Replace-Only** ห้าม Merge ข้อมูลเก่ากับใหม่ปนกัน) และลบไฟล์ `-wal` / `-shm` ที่ค้างอยู่
   - หากเกิดข้อผิดพลาดระหว่างการย้ายไฟล์ ระบบจะนำไฟล์จาก `filesDir/pre_restore_safety/` กลับมาวางคืนทันทีอัตโนมัติ (Crash-Safe Rollback)
   - เปิดการเชื่อมต่อฐานข้อมูลใหม่และบันทึก `BackupMetadataEntity` + `AuditLogEntity`
