package com.oneproject.offline.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Local Operational Role on a single authoritative Android device.
 *
 * Explicit Governance Warning:
 * - Local roles and app lock on a single device provide operational workflow separation only.
 * - They DO NOT constitute remote identity verification, multi-person cryptographic separation,
 *   or root-proof hardware tamper resistance on a rooted/compromised device.
 */
enum class LocalOperationalRole(val labelTh: String) {
    OPERATOR("พนักงานคีย์บิล (Operator)"),
    SUPERVISOR("ผู้ดูแลตรวจทาน/ปลดล็อก (Supervisor)"),
    OWNER_AUDITOR("เจ้าของเครื่อง/ผู้ตรวจสอบบัญชี (Owner / Auditor)")
}

data class SaltedPinRecord(
    val role: LocalOperationalRole,
    val algorithm: String = ALGORITHM_PBKDF2_SHA256,
    val iterations: Int = DEFAULT_PBKDF2_ITERATIONS,
    val saltBase64: String,
    val derivedHashBase64: String,
    val updatedEpochMs: Long,
    val failedAttempts: Int = 0,
    val lockedUntilEpochMs: Long = 0L
) {
    companion object {
        const val ALGORITHM_PBKDF2_SHA256 = "PBKDF2WithHmacSHA256"
        const val DEFAULT_PBKDF2_ITERATIONS = 210_000
    }
}

sealed class PinVerificationResult {
    data class Verified(
        val role: LocalOperationalRole,
        val updatedRecord: SaltedPinRecord
    ) : PinVerificationResult()

    data class InvalidPin(
        val remainingAttemptsBeforeLockout: Int,
        val updatedRecord: SaltedPinRecord,
        val reasonTh: String
    ) : PinVerificationResult()

    data class LockedOut(
        val lockedUntilEpochMs: Long,
        val remainingLockoutMs: Long,
        val reasonTh: String
    ) : PinVerificationResult()
}

enum class DeviceGuardrailStatus {
    HEALTHY,
    BLOCKED_CLOCK_ROLLBACK,
    BLOCKED_LOW_STORAGE,
    BLOCKED_CLOCK_AND_STORAGE
}

data class DeviceGuardrailEvaluation(
    val status: DeviceGuardrailStatus,
    val currentClockEpochMs: Long,
    val latestRecordedEpochMs: Long,
    val clockDriftMs: Long,
    val availableStorageBytes: Long,
    val minRequiredStorageBytes: Long,
    val canCommitAuthoritativeMutations: Boolean,
    val bannerMessageTh: String
)

/**
 * Phase 11 Engine:
 * 1. Salted PBKDF2-HMAC-SHA256 Local App Lock (zero plaintext PIN storage, constant-time compare,
 *    exponential/bounded lockout on repeated failures, explicit single-device limitation warning).
 * 2. Privacy Log Scrubber (redacts PINs, passphrases, 6+ digit secrets, and key-value secrets before logging).
 * 3. Monotonic Clock Rollback (`D12`) & Low-Storage Guardrails blocking authoritative writes when triggered.
 */
object LocalAppLockAndGuardrailsEngine {

    private const val SALT_BYTES = 16
    private const val KEY_LENGTH_BITS = 256
    const val MAX_FAILED_ATTEMPTS = 5
    const val LOCKOUT_DURATION_MS = 5 * 60 * 1000L // 5 minutes
    const val DEFAULT_ALLOWED_CLOCK_DRIFT_MS = 60_000L // 60 seconds tolerance
    const val DEFAULT_MIN_REQUIRED_STORAGE_BYTES = 32L * 1024L * 1024L // 32 MB minimum free space

    const val SINGLE_DEVICE_SECURITY_DISCLAIMER_TH =
        "คำเตือนด้านความปลอดภัย: ระบบล็อกแอปด้วย PIN และบทบาทในเครื่อง (Local Role) ออกแบบมาเพื่อแยกขั้นตอนปฏิบัติงานบนอุปกรณ์ออฟไลน์เครื่องเดียวเท่านั้น ไม่ใช่ระบบพิสูจน์ตัวตนข้ามเครือข่าย และไม่สามารถป้องกันผู้ที่มีสิทธิ์ Root หรือเข้าถึงหน่วยความจำกายภาพของเครื่องโดยตรงได้"

    fun createSaltedPinRecord(
        rawPin: String,
        role: LocalOperationalRole,
        nowEpochMs: Long,
        iterations: Int = SaltedPinRecord.DEFAULT_PBKDF2_ITERATIONS
    ): SaltedPinRecord {
        val normalizedPin = rawPin.trim()
        require(normalizedPin.length in 6..16 && normalizedPin.all { it.isDigit() }) {
            "รหัส PIN ต้องเป็นตัวเลข 6 ถึง 16 หลักเท่านั้น"
        }
        require(iterations >= 120_000) {
            "จำนวนรอบ PBKDF2 ต้องไม่ต่ำกว่า 120,000 รอบ"
        }

        val salt = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(salt)
        val derived = deriveKey(normalizedPin.toCharArray(), salt, iterations)

        return SaltedPinRecord(
            role = role,
            algorithm = SaltedPinRecord.ALGORITHM_PBKDF2_SHA256,
            iterations = iterations,
            saltBase64 = Base64.getEncoder().encodeToString(salt),
            derivedHashBase64 = Base64.getEncoder().encodeToString(derived),
            updatedEpochMs = nowEpochMs,
            failedAttempts = 0,
            lockedUntilEpochMs = 0L
        )
    }

    fun verifyPin(
        candidatePin: String,
        record: SaltedPinRecord,
        nowEpochMs: Long
    ): PinVerificationResult {
        if (record.lockedUntilEpochMs > nowEpochMs) {
            val remainingMs = record.lockedUntilEpochMs - nowEpochMs
            return PinVerificationResult.LockedOut(
                lockedUntilEpochMs = record.lockedUntilEpochMs,
                remainingLockoutMs = remainingMs,
                reasonTh = "บัญชีถูกล็อกชั่วคราวเนื่องจากใส่รหัสผิดเกินกำหนด กรุณารออีก ${(remainingMs / 1000L).coerceAtLeast(1L)} วินาที"
            )
        }

        val normalizedCandidate = candidatePin.trim()
        val salt = Base64.getDecoder().decode(record.saltBase64)
        val expectedHash = Base64.getDecoder().decode(record.derivedHashBase64)
        val candidateHash = deriveKey(normalizedCandidate.toCharArray(), salt, record.iterations)

        val matched = MessageDigest.isEqual(expectedHash, candidateHash)
        if (matched) {
            val resetRecord = record.copy(
                failedAttempts = 0,
                lockedUntilEpochMs = 0L
            )
            return PinVerificationResult.Verified(
                role = record.role,
                updatedRecord = resetRecord
            )
        }

        val baseAttempts = if (record.lockedUntilEpochMs != 0L && record.lockedUntilEpochMs <= nowEpochMs) {
            0
        } else {
            record.failedAttempts
        }
        val nextAttempts = baseAttempts + 1
        return if (nextAttempts >= MAX_FAILED_ATTEMPTS) {
            val lockedUntil = nowEpochMs + LOCKOUT_DURATION_MS
            val lockedRecord = record.copy(
                failedAttempts = nextAttempts,
                lockedUntilEpochMs = lockedUntil
            )
            PinVerificationResult.LockedOut(
                lockedUntilEpochMs = lockedUntil,
                remainingLockoutMs = LOCKOUT_DURATION_MS,
                reasonTh = "ใส่รหัส PIN ผิดครบ $MAX_FAILED_ATTEMPTS ครั้ง ระบบล็อกการเข้าถึงชั่วคราวเป็นเวลา 5 นาที"
            )
        } else {
            val remaining = MAX_FAILED_ATTEMPTS - nextAttempts
            val updatedRecord = record.copy(
                failedAttempts = nextAttempts,
                lockedUntilEpochMs = 0L
            )
            PinVerificationResult.InvalidPin(
                remainingAttemptsBeforeLockout = remaining,
                updatedRecord = updatedRecord,
                reasonTh = "รหัส PIN ไม่ถูกต้อง (เหลือโอกาสอีก $remaining ครั้งก่อนล็อกชั่วคราว)"
            )
        }
    }

    /**
     * Privacy Log Scrubber:
     * Ensures no plaintext PIN, backup passphrase, key material, or secret value leaks into diagnostic logs.
     */
    fun scrubSensitiveText(rawMessage: String): String {
        var scrubbed = rawMessage
        // Redact key=value patterns for pin, passphrase, password, secret, key, token, salt, hash
        val kvRegex = Regex(
            pattern = """(?i)\b(pin|passphrase|password|secret|token|salt|hash|keystore_password|key_password)\s*([:=])\s*([^\s,;]+)""",
        )
        scrubbed = kvRegex.replace(scrubbed) { matchResult ->
            val keyName = matchResult.groupValues[1]
            val separator = matchResult.groupValues[2]
            "$keyName$separator[REDACTED_SECRET]"
        }

        // Redact standalone 6-to-16 digit PIN sequences preceded by Thai/English PIN context keywords
        val contextualPinRegex = Regex(
            pattern = """(?i)(รหัส\s*PIN|PIN|รหัสผ่าน|Passphrase)\s*[:=]?\s*[0-9]{4,16}"""
        )
        scrubbed = contextualPinRegex.replace(scrubbed) { matchResult ->
            val prefix = matchResult.groupValues[1]
            "$prefix: [REDACTED_PIN]"
        }

        return scrubbed
    }

    /**
     * Evaluates monotonic clock rollback (`D12`) and minimum storage free space guardrails.
     * Authoritative commits (Bill Confirm, Edit, Cancel, Settlement, Transfer Batch) MUST be blocked
     * if either clock rollback exceeds allowed drift or free storage drops below threshold.
     */
    fun evaluateDeviceHealth(
        currentClockEpochMs: Long,
        latestRecordedEpochMs: Long,
        availableStorageBytes: Long,
        allowedClockDriftMs: Long = DEFAULT_ALLOWED_CLOCK_DRIFT_MS,
        minRequiredStorageBytes: Long = DEFAULT_MIN_REQUIRED_STORAGE_BYTES
    ): DeviceGuardrailEvaluation {
        val clockDriftMs = currentClockEpochMs - latestRecordedEpochMs
        val isClockRolledBack = latestRecordedEpochMs > 0L && clockDriftMs < -allowedClockDriftMs
        val isLowStorage = availableStorageBytes < minRequiredStorageBytes

        val status = when {
            isClockRolledBack && isLowStorage -> DeviceGuardrailStatus.BLOCKED_CLOCK_AND_STORAGE
            isClockRolledBack -> DeviceGuardrailStatus.BLOCKED_CLOCK_ROLLBACK
            isLowStorage -> DeviceGuardrailStatus.BLOCKED_LOW_STORAGE
            else -> DeviceGuardrailStatus.HEALTHY
        }

        val availableMb = availableStorageBytes / (1024L * 1024L)
        val requiredMb = minRequiredStorageBytes / (1024L * 1024L)
        val rollbackSeconds = if (clockDriftMs < 0L) (-clockDriftMs / 1000L) else 0L

        val bannerMessageTh = when (status) {
            DeviceGuardrailStatus.HEALTHY ->
                "สถานะเวลาและพื้นที่จัดเก็บปกติ (พื้นที่ว่าง $availableMb MB / ขั้นต่ำ $requiredMb MB) พร้อมบันทึกธุรกรรมในเครื่อง"
            DeviceGuardrailStatus.BLOCKED_CLOCK_ROLLBACK ->
                "ระงับการบันทึกธุรกรรมชั่วคราว (D12): ตรวจพบเวลาเครื่องย้อนหลังกว่าที่เคยบันทึกไว้ $rollbackSeconds วินาที กรุณาตั้งเวลาอุปกรณ์ให้ถูกต้องก่อนทำรายการ"
            DeviceGuardrailStatus.BLOCKED_LOW_STORAGE ->
                "ระงับการบันทึกธุรกรรมชั่วคราว: พื้นที่ว่างในเครื่องเหลือเพียง $availableMb MB (ต่ำกว่าเกณฑ์ปลอดภัย $requiredMb MB สำหรับ SQLite WAL) กรุณาเพิ่มพื้นที่ว่างก่อนทำรายการ"
            DeviceGuardrailStatus.BLOCKED_CLOCK_AND_STORAGE ->
                "ระงับการบันทึกธุรกรรมชั่วคราว: ตรวจพบเวลาเครื่องย้อนหลัง ($rollbackSeconds วินาที) และพื้นที่ว่างต่ำกว่าเกณฑ์ ($availableMb MB / $requiredMb MB)"
        }

        return DeviceGuardrailEvaluation(
            status = status,
            currentClockEpochMs = currentClockEpochMs,
            latestRecordedEpochMs = latestRecordedEpochMs,
            clockDriftMs = clockDriftMs,
            availableStorageBytes = availableStorageBytes,
            minRequiredStorageBytes = minRequiredStorageBytes,
            canCommitAuthoritativeMutations = status == DeviceGuardrailStatus.HEALTHY,
            bannerMessageTh = bannerMessageTh
        )
    }

    private fun deriveKey(passwordChars: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passwordChars, salt, iterations, KEY_LENGTH_BITS)
        return try {
            val skf = SecretKeyFactory.getInstance(SaltedPinRecord.ALGORITHM_PBKDF2_SHA256)
            skf.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            passwordChars.fill('\u0000')
        }
    }
}
