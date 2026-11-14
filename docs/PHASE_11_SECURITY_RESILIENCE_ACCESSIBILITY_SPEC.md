# Phase 11 — Local App Lock, Privacy Scrubber, Clock/Storage Guardrails & Accessibility Spec

## 1. ขอบเขตความปลอดภัยบนอุปกรณ์ออฟไลน์เครื่องเดียว (Single-Device Security Boundary)
- **Salted PBKDF2-HMAC-SHA256 Local App Lock**:
  - ใช้ `PBKDF2WithHmacSHA256` (`210,000` รอบ, Salt สุ่ม `16` ไบต์จาก `SecureRandom`) และเปรียบเทียบค่า Hash แบบ Constant-Time ด้วย `MessageDigest.isEqual`
  - ห้ามเก็บรหัส PIN เป็นข้อความธรรมดา (Plaintext) ลงฐานข้อมูลหรือ SharedPreferences
  - ล็อกชั่วคราว 5 นาทีเมื่อใส่ PIN ผิดติดต่อกันครบ 5 ครั้ง
- **คำเตือนข้อจำกัดที่ชัดเจน**:
  - บทบาทในเครื่อง (`OPERATOR`, `SUPERVISOR`, `OWNER_AUDITOR`) และ PIN Lock มีไว้เพื่อแยกขั้นตอนการทำงานบนอุปกรณ์เครื่องเดียวเท่านั้น ไม่ใช่การพิสูจน์ตัวตนข้ามเครือข่าย และไม่สามารถป้องกันผู้ที่มีสิทธิ์ Root บนอุปกรณ์ได้
- **Database-at-Rest (`D10`)**:
  - Room ไม่ได้เข้ารหัสไฟล์ `.db` ขณะพักข้อมูลโดยอัตโนมัติ (ในขณะที่ไฟล์สำรอง `.1pbackup` เข้ารหัสด้วย `PBKDF2 + AES-256-GCM` แล้วใน Phase 06B) โดย `D10` ยังคงเป็น Blocker ก่อนนำข้อมูลจริงเชิงพาณิชย์ขึ้นระบบ

## 2. การกรองข้อมูลความลับออกจาก Log (`PrivacyLogScrubber`)
- กรองค่า `pin`, `passphrase`, `password`, `secret`, `token`, `salt`, `hash`, `keystore_password`, `key_password` รวมถึงรหัสตัวเลขหลังคำว่า `PIN` / `รหัส PIN` ให้เป็น `[REDACTED_SECRET]` หรือ `[REDACTED_PIN]` ก่อนส่งออกสู่ Log

## 3. การป้องกันเวลาเครื่องย้อนกลับ (`D12`) และพื้นที่จัดเก็บใกล้เต็ม
- `LocalAppLockAndGuardrailsEngine.evaluateDeviceHealth(...)` ตรวจสอบ:
  1. **Monotonic Clock Rollback (`D12`)**: หากเวลาปัจจุบันของเครื่องย้อนหลังกว่าเวลาธุรกรรมล่าสุดเกิน `60` วินาที ระบบจะเปลี่ยนสถานะเป็น `BLOCKED_CLOCK_ROLLBACK` และไม่อนุญาตให้ Commit ธุรกรรมใหม่จนกว่าจะตั้งเวลาให้ถูกต้อง
  2. **Low-Storage Guard**: หากพื้นที่ว่างต่ำกว่า `32 MB` ระบบจะเปลี่ยนสถานะเป็น `BLOCKED_LOW_STORAGE` เพื่อป้องกันฐานข้อมูล SQLite WAL เสียหายระหว่าง Commit
