package com.oneproject.offline.core.security

/**
 * ข้อกำหนดโครงสร้างและค่าคงที่ด้านความปลอดภัยของระบบสำรองข้อมูล (Phase 06A Approved-Pending Design Spec)
 * กฎข้อ 01 & 04_SPEC:
 * - ไม่ใช้การเข้ารหัสที่คิดขึ้นเอง (No ad-hoc crypto)
 * - ไม่พึ่งพา Android Keystore ของเครื่องเก่าสำหรับการกู้คืนข้ามเครื่อง (Device Keystore keys are not assumed portable)
 * - ใช้ PBKDF2WithHmacSHA256 + AES/GCM/NoPadding (128-bit Auth Tag) พร้อม AAD ผูก Header
 * - บังคับทำ WAL Checkpoint ก่อนสร้าง Snapshot และบังคับตรวจใน Staging ก่อน Replace-Only เสมอ
 */
object BackupDesignSpec {

    const val ARCHIVE_MAGIC_HEADER: String = "1PROJ_BK"
    const val ARCHIVE_FORMAT_VERSION: Int = 1
    const val REQUIRED_ROOM_SCHEMA_VERSION: Int = 2

    // Vetted Platform JCA Algorithms (Android API 26+)
    const val KDF_ALGORITHM: String = "PBKDF2WithHmacSHA256"
    const val KDF_ITERATION_COUNT: Int = 210_000
    const val KDF_SALT_LENGTH_BYTES: Int = 16
    const val AES_KEY_LENGTH_BITS: Int = 256

    const val AEAD_CIPHER_TRANSFORMATION: String = "AES/GCM/NoPadding"
    const val GCM_IV_LENGTH_BYTES: Int = 12
    const val GCM_TAG_LENGTH_BITS: Int = 128

    const val MIN_PASSPHRASE_LENGTH_CHARS: Int = 8
    const val STORAGE_SAFETY_MULTIPLIER: Long = 3L
}

/**
 * โครงสร้างข้อมูลส่วนหัวของไฟล์สำรองข้อมูลที่ถูกผูกเข้ากับ AES-GCM AAD (Additional Authenticated Data)
 * หากข้อมูลส่วนนี้ถูกแก้ไขแม้แต่ 1 บิต การตรวจสอบ GCM Authentication Tag จะล้มเหลวทันที
 */
data class AuthenticatedBackupHeader(
    val magicHeader: String = BackupDesignSpec.ARCHIVE_MAGIC_HEADER,
    val archiveFormatVersion: Int = BackupDesignSpec.ARCHIVE_FORMAT_VERSION,
    val roomSchemaVersion: Int = BackupDesignSpec.REQUIRED_ROOM_SCHEMA_VERSION,
    val archiveId: String,
    val createdAtEpochMs: Long,
    val walCheckpointVerified: Boolean,
    val kdfIterationCount: Int = BackupDesignSpec.KDF_ITERATION_COUNT,
    val saltHex: String,
    val ivHex: String,
    val payloadUnencryptedSizeBytes: Long,
    val payloadSha256Hex: String
) {
    /**
     * สร้าง Canonical String สำหรับใช้เป็น Additional Authenticated Data (AAD) ใน AES-256-GCM
     */
    fun toCanonicalAadString(): String {
        return listOf(
            "magic=$magicHeader",
            "fmt=$archiveFormatVersion",
            "schema=$roomSchemaVersion",
            "id=$archiveId",
            "ts=$createdAtEpochMs",
            "wal=$walCheckpointVerified",
            "iter=$kdfIterationCount",
            "salt=$saltHex",
            "iv=$ivHex",
            "size=$payloadUnencryptedSizeBytes",
            "sha256=$payloadSha256Hex"
        ).joinToString("|")
    }
}

/**
 * รหัสปฏิเสธการสำรองหรือกู้คืนข้อมูลอย่างชัดเจน (เตรียมสำหรับใช้งานจริงใน Phase 06B)
 */
enum class BackupRestoreErrorCode(val thaiDescription: String) {
    PASSPHRASE_TOO_SHORT("รหัสผ่านสำหรับสำรองข้อมูลสั้นเกินไป (ต้องมีอย่างน้อย 8 ตัวอักษร)"),
    WAL_CHECKPOINT_BUSY("ไม่สามารถรวมไฟล์ WAL ได้เนื่องจากยังมีธุรกรรมค้างอยู่ กรุณาลองใหม่"),
    INSUFFICIENT_STORAGE_SPACE("พื้นที่ว่างในเครื่องไม่เพียงพอสำหรับการทำงานในพื้นที่ Staging อย่างปลอดภัย"),
    INVALID_ARCHIVE_HEADER("รูปแบบไฟล์สำรองไม่ถูกต้อง หรือไม่ใช่ไฟล์สำรองของ 1Project"),
    UNSUPPORTED_ARCHIVE_VERSION("รุ่นรูปแบบไฟล์สำรองไม่รองรับในแอปเวอร์ชันนี้"),
    INCOMPATIBLE_SCHEMA_VERSION("รุ่นโครงสร้างฐานข้อมูล (Schema Version) ในไฟล์สำรองไม่ตรงกับระบบปัจจุบัน"),
    WRONG_PASSWORD_OR_CORRUPT_ARCHIVE("รหัสผ่านไม่ถูกต้อง หรือไฟล์สำรองถูกแก้ไข/เสียหาย (AES-GCM Tag Verification Failed)"),
    PAYLOAD_HASH_MISMATCH("ค่า SHA-256 ของฐานข้อมูลหลังถอดรหัสไม่ตรงกับส่วนหัวที่ยืนยันแล้ว"),
    SQLITE_INTEGRITY_CHECK_FAILED("ไฟล์ฐานข้อมูลใน Staging ไม่ผ่านการตรวจ PRAGMA integrity_check หรือ foreign_key_check")
}
