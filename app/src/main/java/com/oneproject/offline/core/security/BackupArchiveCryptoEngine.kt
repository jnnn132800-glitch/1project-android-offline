package com.oneproject.offline.core.security

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * กลไกเข้ารหัสและตรวจสอบความถูกต้องของไฟล์สำรองข้อมูล (.1pbackup) สำหรับ Phase 06B
 *
 * กฎข้อ 01, 04_SPEC (หมวด 5.7) และ D10:
 * 1. ใช้เฉพาะอัลกอริทึมมาตรฐานใน Android JCA (`PBKDF2WithHmacSHA256` + `AES/GCM/NoPadding`) ห้ามประดิษฐ์ Crypto เอง
 * 2. ไม่พึ่งพา Android Keystore ของเครื่องเก่า เพื่อให้กู้คืนข้ามเครื่องได้จริงเมื่อเครื่องเดิมพังหรือสูญหาย
 * 3. ผูกข้อมูลส่วนหัวทั้งหมดเข้ากับ AES-GCM Additional Authenticated Data (AAD) หากส่วนหัวหรือเนื้อหาถูกแก้แม้แต่ 1 บิต
 *    การถอดรหัสจะล้มเหลวด้วย AEADBadTagException ทันที
 * 4. ล้างข้อมูลรหัสผ่านและกุญแจออกจากหน่วยความจำ (`fill(0)`) ในบล็อก `finally` และไม่เขียนข้อมูลลับลง Log
 */
object BackupArchiveCryptoEngine {

    private const val MAX_HEADER_LENGTH_BYTES: Int = 16_384
    const val STORAGE_HEADROOM_BYTES: Long = 33_554_432L // 32 MB

    sealed class ArchiveWriteOutcome {
        data class Success(
            val header: AuthenticatedBackupHeader,
            val totalArchiveBytesWritten: Long
        ) : ArchiveWriteOutcome()

        data class Rejected(
            val errorCode: BackupRestoreErrorCode,
            val detailTh: String
        ) : ArchiveWriteOutcome()
    }

    sealed class ArchiveReadToStagingOutcome {
        data class Success(
            val header: AuthenticatedBackupHeader,
            val stagingDatabaseFile: File
        ) : ArchiveReadToStagingOutcome()

        data class Rejected(
            val errorCode: BackupRestoreErrorCode,
            val detailTh: String
        ) : ArchiveReadToStagingOutcome()
    }

    /**
     * คำนวณพื้นที่ว่างขั้นต่ำที่ต้องมีในเครื่องก่อนทำ Staging Backup หรือ Staging Restore
     */
    fun computeRequiredAvailableStorageBytes(uncompressedDbSizeBytes: Long): Long {
        val safeDbSize = if (uncompressedDbSizeBytes < 0L) 0L else uncompressedDbSizeBytes
        return Math.addExact(
            Math.multiplyExact(safeDbSize, BackupDesignSpec.STORAGE_SAFETY_MULTIPLIER),
            STORAGE_HEADROOM_BYTES
        )
    }

    /**
     * เขียนไฟล์สำรองข้อมูลแบบยืนยันความถูกต้อง (.1pbackup)
     * โครงสร้างไบนารี:
     * - [8 bytes] ASCII Magic Header ("1PROJ_BK")
     * - [4 bytes] Int: ความยาวของ Canonical AAD Header (UTF-8 bytes)
     * - [N bytes] UTF-8 String: Canonical AAD Header (`key=value|...`)
     * - [4 bytes] Int: ความยาวของ Ciphertext + 128-bit GCM Tag
     * - [M bytes] AES-256-GCM Ciphertext + Authentication Tag
     */
    fun writeAuthenticatedArchive(
        snapshotDatabaseBytes: ByteArray,
        passphrase: CharArray,
        archiveId: String,
        createdAtEpochMs: Long,
        walCheckpointVerified: Boolean,
        availableStorageBytes: Long,
        outputStream: OutputStream,
        roomSchemaVersion: Int = BackupDesignSpec.REQUIRED_ROOM_SCHEMA_VERSION,
        secureRandom: SecureRandom = SecureRandom()
    ): ArchiveWriteOutcome {
        var derivedKeyBytes: ByteArray? = null
        try {
            if (passphrase.size < BackupDesignSpec.MIN_PASSPHRASE_LENGTH_CHARS) {
                return ArchiveWriteOutcome.Rejected(
                    errorCode = BackupRestoreErrorCode.PASSPHRASE_TOO_SHORT,
                    detailTh = BackupRestoreErrorCode.PASSPHRASE_TOO_SHORT.thaiDescription
                )
            }

            if (!walCheckpointVerified) {
                return ArchiveWriteOutcome.Rejected(
                    errorCode = BackupRestoreErrorCode.WAL_CHECKPOINT_BUSY,
                    detailTh = BackupRestoreErrorCode.WAL_CHECKPOINT_BUSY.thaiDescription
                )
            }

            val requiredBytes = computeRequiredAvailableStorageBytes(snapshotDatabaseBytes.size.toLong())
            if (availableStorageBytes < requiredBytes) {
                return ArchiveWriteOutcome.Rejected(
                    errorCode = BackupRestoreErrorCode.INSUFFICIENT_STORAGE_SPACE,
                    detailTh = "${BackupRestoreErrorCode.INSUFFICIENT_STORAGE_SPACE.thaiDescription} (ต้องการอย่างน้อย ${requiredBytes / 1024} KB)"
                )
            }

            val salt = ByteArray(BackupDesignSpec.KDF_SALT_LENGTH_BYTES)
            secureRandom.nextBytes(salt)

            val iv = ByteArray(BackupDesignSpec.GCM_IV_LENGTH_BYTES)
            secureRandom.nextBytes(iv)

            val payloadSha256Hex = sha256Hex(snapshotDatabaseBytes)

            val header = AuthenticatedBackupHeader(
                magicHeader = BackupDesignSpec.ARCHIVE_MAGIC_HEADER,
                archiveFormatVersion = BackupDesignSpec.ARCHIVE_FORMAT_VERSION,
                roomSchemaVersion = roomSchemaVersion,
                archiveId = archiveId,
                createdAtEpochMs = createdAtEpochMs,
                walCheckpointVerified = true,
                kdfIterationCount = BackupDesignSpec.KDF_ITERATION_COUNT,
                saltHex = bytesToHex(salt),
                ivHex = bytesToHex(iv),
                payloadUnencryptedSizeBytes = snapshotDatabaseBytes.size.toLong(),
                payloadSha256Hex = payloadSha256Hex
            )

            val canonicalAadBytes = header.toCanonicalAadString().toByteArray(StandardCharsets.UTF_8)
            derivedKeyBytes = deriveAes256Key(
                passphrase = passphrase,
                salt = salt,
                iterations = header.kdfIterationCount
            )

            val cipher = Cipher.getInstance(BackupDesignSpec.AEAD_CIPHER_TRANSFORMATION)
            val keySpec = SecretKeySpec(derivedKeyBytes, "AES")
            val gcmSpec = GCMParameterSpec(BackupDesignSpec.GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
            cipher.updateAAD(canonicalAadBytes)

            val cipherTextWithTag = cipher.doFinal(snapshotDatabaseBytes)

            val dataOut = DataOutputStream(outputStream)
            val magicBytes = BackupDesignSpec.ARCHIVE_MAGIC_HEADER.toByteArray(StandardCharsets.US_ASCII)
            dataOut.write(magicBytes)
            dataOut.writeInt(canonicalAadBytes.size)
            dataOut.write(canonicalAadBytes)
            dataOut.writeInt(cipherTextWithTag.size)
            dataOut.write(cipherTextWithTag)
            dataOut.flush()

            val totalWritten = magicBytes.size.toLong() +
                4L + canonicalAadBytes.size.toLong() +
                4L + cipherTextWithTag.size.toLong()

            return ArchiveWriteOutcome.Success(
                header = header,
                totalArchiveBytesWritten = totalWritten
            )
        } finally {
            derivedKeyBytes?.fill(0)
        }
    }

    /**
     * อ่านและตรวจสอบความถูกต้องของไฟล์สำรอง (.1pbackup) พร้อมถอดรหัสลงเฉพาะไฟล์ในพื้นที่ Staging เท่านั้น
     * ห้ามเขียนทับฐานข้อมูลจริงโดยตรงเด็ดขาด
     */
    fun readAndAuthenticateArchiveToStaging(
        inputStream: InputStream,
        passphrase: CharArray,
        stagingFile: File,
        availableStorageBytes: Long,
        requiredRoomSchemaVersion: Int = BackupDesignSpec.REQUIRED_ROOM_SCHEMA_VERSION
    ): ArchiveReadToStagingOutcome {
        var derivedKeyBytes: ByteArray? = null
        try {
            if (passphrase.size < BackupDesignSpec.MIN_PASSPHRASE_LENGTH_CHARS) {
                return ArchiveReadToStagingOutcome.Rejected(
                    errorCode = BackupRestoreErrorCode.PASSPHRASE_TOO_SHORT,
                    detailTh = BackupRestoreErrorCode.PASSPHRASE_TOO_SHORT.thaiDescription
                )
            }

            val dataIn = DataInputStream(inputStream)
            val expectedMagicBytes = BackupDesignSpec.ARCHIVE_MAGIC_HEADER.toByteArray(StandardCharsets.US_ASCII)
            val actualMagicBytes = ByteArray(expectedMagicBytes.size)
            try {
                dataIn.readFully(actualMagicBytes)
            } catch (e: Exception) {
                return ArchiveReadToStagingOutcome.Rejected(
                    errorCode = BackupRestoreErrorCode.INVALID_ARCHIVE_HEADER,
                    detailTh = BackupRestoreErrorCode.INVALID_ARCHIVE_HEADER.thaiDescription
                )
            }

            val actualMagic = String(actualMagicBytes, StandardCharsets.US_ASCII)
            if (actualMagic != BackupDesignSpec.ARCHIVE_MAGIC_HEADER) {
                return ArchiveReadToStagingOutcome.Rejected(
                    errorCode = BackupRestoreErrorCode.INVALID_ARCHIVE_HEADER,
                    detailTh = BackupRestoreErrorCode.INVALID_ARCHIVE_HEADER.thaiDescription
                )
            }

            val headerLength = try {
                dataIn.readInt()
            } catch (e: Exception) {
                return ArchiveReadToStagingOutcome.Rejected(
                    errorCode = BackupRestoreErrorCode.INVALID_ARCHIVE_HEADER,
                    detailTh = BackupRestoreErrorCode.INVALID_ARCHIVE_HEADER.thaiDescription
                )
            }

            if (headerLength <= 0 || headerLength > MAX_HEADER_LENGTH_BYTES) {
                return ArchiveReadToStagingOutcome.Rejected(
                    errorCode = BackupRestoreErrorCode.INVALID_ARCHIVE_HEADER,
                    detailTh = BackupRestoreErrorCode.INVALID_ARCHIVE_HEADER.thaiDescription
                )
            }

            val canonicalAadBytes = ByteArray(headerLength)
            try {
                dataIn.readFully(canonicalAadBytes)
            } catch (e: Exception) {
                return ArchiveReadToStagingOutcome.Rejected(
                    errorCode = BackupRestoreErrorCode.INVALID_ARCHIVE_HEADER,
                    detailTh = BackupRestoreErrorCode.INVALID_ARCHIVE_HEADER.thaiDescription
                )
            }

            val canonicalAadString = String(canonicalAadBytes, StandardCharsets.UTF_8)
            val parsedHeader = parseCanonicalAadString(canonicalAadString)
                ?: return ArchiveReadToStagingOutcome.Rejected(
                    errorCode = BackupRestoreErrorCode.INVALID_ARCHIVE_HEADER,
                    detailTh = BackupRestoreErrorCode.INVALID_ARCHIVE_HEADER.thaiDescription
                )

            if (parsedHeader.magicHeader != BackupDesignSpec.ARCHIVE_MAGIC_HEADER) {
                return ArchiveReadToStagingOutcome.Rejected(
                    errorCode = BackupRestoreErrorCode.INVALID_ARCHIVE_HEADER,
                    detailTh = BackupRestoreErrorCode.INVALID_ARCHIVE_HEADER.thaiDescription
                )
            }

            if (parsedHeader.archiveFormatVersion != BackupDesignSpec.ARCHIVE_FORMAT_VERSION) {
                return ArchiveReadToStagingOutcome.Rejected(
                    errorCode = BackupRestoreErrorCode.UNSUPPORTED_ARCHIVE_VERSION,
                    detailTh = "${BackupRestoreErrorCode.UNSUPPORTED_ARCHIVE_VERSION.thaiDescription} (พบ format=${parsedHeader.archiveFormatVersion})"
                )
            }

            if (parsedHeader.roomSchemaVersion != requiredRoomSchemaVersion) {
                return ArchiveReadToStagingOutcome.Rejected(
                    errorCode = BackupRestoreErrorCode.INCOMPATIBLE_SCHEMA_VERSION,
                    detailTh = "${BackupRestoreErrorCode.INCOMPATIBLE_SCHEMA_VERSION.thaiDescription} (พบ schema=${parsedHeader.roomSchemaVersion}, ต้องการ=$requiredRoomSchemaVersion)"
                )
            }

            if (!parsedHeader.walCheckpointVerified) {
                return ArchiveReadToStagingOutcome.Rejected(
                    errorCode = BackupRestoreErrorCode.WAL_CHECKPOINT_BUSY,
                    detailTh = "ไฟล์สำรองนี้ไม่ได้ผ่านการยืนยัน WAL Checkpoint"
                )
            }

            val requiredStorage = computeRequiredAvailableStorageBytes(parsedHeader.payloadUnencryptedSizeBytes)
            if (availableStorageBytes < requiredStorage) {
                return ArchiveReadToStagingOutcome.Rejected(
                    errorCode = BackupRestoreErrorCode.INSUFFICIENT_STORAGE_SPACE,
                    detailTh = "${BackupRestoreErrorCode.INSUFFICIENT_STORAGE_SPACE.thaiDescription} (ต้องการอย่างน้อย ${requiredStorage / 1024} KB)"
                )
            }

            val cipherLength = try {
                dataIn.readInt()
            } catch (e: Exception) {
                return ArchiveReadToStagingOutcome.Rejected(
                    errorCode = BackupRestoreErrorCode.WRONG_PASSWORD_OR_CORRUPT_ARCHIVE,
                    detailTh = BackupRestoreErrorCode.WRONG_PASSWORD_OR_CORRUPT_ARCHIVE.thaiDescription
                )
            }

            if (cipherLength <= BackupDesignSpec.GCM_TAG_LENGTH_BITS / 8) {
                return ArchiveReadToStagingOutcome.Rejected(
                    errorCode = BackupRestoreErrorCode.WRONG_PASSWORD_OR_CORRUPT_ARCHIVE,
                    detailTh = BackupRestoreErrorCode.WRONG_PASSWORD_OR_CORRUPT_ARCHIVE.thaiDescription
                )
            }

            val cipherBytes = ByteArray(cipherLength)
            try {
                dataIn.readFully(cipherBytes)
            } catch (e: Exception) {
                return ArchiveReadToStagingOutcome.Rejected(
                    errorCode = BackupRestoreErrorCode.WRONG_PASSWORD_OR_CORRUPT_ARCHIVE,
                    detailTh = BackupRestoreErrorCode.WRONG_PASSWORD_OR_CORRUPT_ARCHIVE.thaiDescription
                )
            }

            val saltBytes = hexToBytes(parsedHeader.saltHex)
            val ivBytes = hexToBytes(parsedHeader.ivHex)
            if (saltBytes == null || saltBytes.size != BackupDesignSpec.KDF_SALT_LENGTH_BYTES ||
                ivBytes == null || ivBytes.size != BackupDesignSpec.GCM_IV_LENGTH_BYTES
            ) {
                return ArchiveReadToStagingOutcome.Rejected(
                    errorCode = BackupRestoreErrorCode.INVALID_ARCHIVE_HEADER,
                    detailTh = BackupRestoreErrorCode.INVALID_ARCHIVE_HEADER.thaiDescription
                )
            }

            derivedKeyBytes = deriveAes256Key(
                passphrase = passphrase,
                salt = saltBytes,
                iterations = parsedHeader.kdfIterationCount
            )

            val decryptedBytes = try {
                val cipher = Cipher.getInstance(BackupDesignSpec.AEAD_CIPHER_TRANSFORMATION)
                val keySpec = SecretKeySpec(derivedKeyBytes, "AES")
                val gcmSpec = GCMParameterSpec(BackupDesignSpec.GCM_TAG_LENGTH_BITS, ivBytes)
                cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
                cipher.updateAAD(canonicalAadBytes)
                cipher.doFinal(cipherBytes)
            } catch (badTag: AEADBadTagException) {
                safeDeleteFile(stagingFile)
                return ArchiveReadToStagingOutcome.Rejected(
                    errorCode = BackupRestoreErrorCode.WRONG_PASSWORD_OR_CORRUPT_ARCHIVE,
                    detailTh = BackupRestoreErrorCode.WRONG_PASSWORD_OR_CORRUPT_ARCHIVE.thaiDescription
                )
            } catch (securityEx: GeneralSecurityException) {
                safeDeleteFile(stagingFile)
                return ArchiveReadToStagingOutcome.Rejected(
                    errorCode = BackupRestoreErrorCode.WRONG_PASSWORD_OR_CORRUPT_ARCHIVE,
                    detailTh = BackupRestoreErrorCode.WRONG_PASSWORD_OR_CORRUPT_ARCHIVE.thaiDescription
                )
            }

            if (decryptedBytes.size.toLong() != parsedHeader.payloadUnencryptedSizeBytes) {
                safeDeleteFile(stagingFile)
                return ArchiveReadToStagingOutcome.Rejected(
                    errorCode = BackupRestoreErrorCode.PAYLOAD_HASH_MISMATCH,
                    detailTh = BackupRestoreErrorCode.PAYLOAD_HASH_MISMATCH.thaiDescription
                )
            }

            val actualSha256Hex = sha256Hex(decryptedBytes)
            if (!actualSha256Hex.equals(parsedHeader.payloadSha256Hex, ignoreCase = true)) {
                safeDeleteFile(stagingFile)
                return ArchiveReadToStagingOutcome.Rejected(
                    errorCode = BackupRestoreErrorCode.PAYLOAD_HASH_MISMATCH,
                    detailTh = BackupRestoreErrorCode.PAYLOAD_HASH_MISMATCH.thaiDescription
                )
            }

            stagingFile.parentFile?.mkdirs()
            FileOutputStream(stagingFile).use { fos ->
                fos.write(decryptedBytes)
                fos.fd.sync()
            }

            return ArchiveReadToStagingOutcome.Success(
                header = parsedHeader,
                stagingDatabaseFile = stagingFile
            )
        } finally {
            derivedKeyBytes?.fill(0)
        }
    }

    private fun deriveAes256Key(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int
    ): ByteArray {
        val spec = PBEKeySpec(
            passphrase,
            salt,
            iterations,
            BackupDesignSpec.AES_KEY_LENGTH_BITS
        )
        try {
            val factory = SecretKeyFactory.getInstance(BackupDesignSpec.KDF_ALGORITHM)
            return factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun parseCanonicalAadString(aad: String): AuthenticatedBackupHeader? {
        val parts = aad.split("|")
        val map = mutableMapOf<String, String>()
        for (part in parts) {
            val idx = part.indexOf('=')
            if (idx <= 0) return null
            val key = part.substring(0, idx)
            val value = part.substring(idx + 1)
            map[key] = value
        }
        return try {
            val header = AuthenticatedBackupHeader(
                magicHeader = map["magic"] ?: return null,
                archiveFormatVersion = map["fmt"]?.toInt() ?: return null,
                roomSchemaVersion = map["schema"]?.toInt() ?: return null,
                archiveId = map["id"] ?: return null,
                createdAtEpochMs = map["ts"]?.toLong() ?: return null,
                walCheckpointVerified = map["wal"]?.toBooleanStrict() ?: return null,
                kdfIterationCount = map["iter"]?.toInt() ?: return null,
                saltHex = map["salt"] ?: return null,
                ivHex = map["iv"] ?: return null,
                payloadUnencryptedSizeBytes = map["size"]?.toLong() ?: return null,
                payloadSha256Hex = map["sha256"] ?: return null
            )
            if (header.toCanonicalAadString() != aad) null else header
        } catch (e: Exception) {
            null
        }
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return bytesToHex(digest.digest(bytes))
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append( Character.forDigit(v ushr 4, 16) )
            sb.append( Character.forDigit(v and 0x0F, 16) )
        }
        return sb.toString()
    }

    fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi == -1 || lo == -1) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun safeDeleteFile(file: File) {
        if (file.exists()) {
            file.delete()
        }
    }
}
