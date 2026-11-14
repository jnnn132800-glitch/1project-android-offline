package com.oneproject.offline.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.StatFs
import com.oneproject.offline.core.security.AuthenticatedBackupHeader
import com.oneproject.offline.core.security.BackupArchiveCryptoEngine
import com.oneproject.offline.core.security.BackupDesignSpec
import com.oneproject.offline.core.security.BackupRestoreErrorCode
import com.oneproject.offline.data.local.OneProjectDatabase
import com.oneproject.offline.data.local.entity.AuditLogEntity
import com.oneproject.offline.data.local.entity.BackupMetadataEntity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * ผลลัพธ์การทำ PRAGMA wal_checkpoint(TRUNCATE)
 */
data class WalCheckpointResult(
    val busy: Int,
    val logFrames: Int,
    val checkpointedFrames: Int
) {
    val isConsistentAndComplete: Boolean
        get() = busy == 0 && (logFrames == -1 || logFrames == checkpointedFrames)
}

/**
 * รายงานผลการตรวจสอบความสมบูรณ์ของฐานข้อมูล SQLite ในพื้นที่ Staging ก่อนอนุญาตให้ Replace-Only
 */
data class StagingSqliteValidationReport(
    val isValid: Boolean,
    val integrityCheckResult: String,
    val foreignKeyViolationCount: Int,
    val userSchemaVersion: Int,
    val missingCoreTables: List<String>,
    val previewRoundCode: String,
    val previewBillCount: Int,
    val previewAuditCount: Int
)

/**
 * ข้อมูลตัวอย่างของไฟล์สำรองที่ผ่านการตรวจ AES-256-GCM Tag, SHA-256 และ SQLite Integrity ในพื้นที่ Staging แล้ว
 * รอให้ผู้ใช้กดยืนยันขั้นสุดท้ายเพื่อเขียนทับแบบ Replace-Only (Never Merge)
 */
data class StagingRestorePreview(
    val stagingTokenId: String,
    val header: AuthenticatedBackupHeader,
    val stagingDatabaseFile: File,
    val validationReport: StagingSqliteValidationReport
)

sealed class BackupExportResult {
    data class Success(
        val archiveId: String,
        val header: AuthenticatedBackupHeader,
        val totalBytesWritten: Long,
        val metadataRecorded: BackupMetadataEntity
    ) : BackupExportResult()

    data class Rejected(
        val errorCode: BackupRestoreErrorCode,
        val messageTh: String
    ) : BackupExportResult()
}

sealed class StagingPreviewResult {
    data class ReadyForReplaceConfirmation(
        val preview: StagingRestorePreview
    ) : StagingPreviewResult()

    data class Rejected(
        val errorCode: BackupRestoreErrorCode,
        val messageTh: String
    ) : StagingPreviewResult()
}

sealed class RestoreExecutionResult {
    data class Success(
        val archiveId: String,
        val restoredRoundCode: String,
        val restoredBillCount: Int,
        val rollbackBackupFilePreserved: Boolean,
        val metadataRecorded: BackupMetadataEntity
    ) : RestoreExecutionResult()

    data class Rejected(
        val errorCode: BackupRestoreErrorCode,
        val messageTh: String,
        val rolledBackToPreviousDb: Boolean
    ) : RestoreExecutionResult()
}

/**
 * พอร์ตเชื่อมต่อฐานข้อมูลและระบบไฟล์ เพื่อให้ทดสอบทั้งบน Android จริงและใน JUnit Test (Cross-device / Corrupt / Low-storage / Fault injection) ได้ครบถ้วน
 */
interface BackupDatabasePort {
    fun getActiveDatabaseFile(): File
    fun getStagingDirectory(): File
    fun getAvailableStorageBytes(): Long
    suspend fun performWalCheckpointTruncate(): WalCheckpointResult
    fun validateStagingSqliteFile(stagingFile: File): StagingSqliteValidationReport
    suspend fun closeActiveDatabaseForSwap()
    suspend fun reopenDatabaseAndRecordMetadata(
        metadata: BackupMetadataEntity,
        auditSummaryTh: String
    )
}

/**
 * ตัวจัดการการสำรองและกู้คืนข้อมูลแบบยืนยันความถูกต้อง (Phase 06B Implementation)
 *
 * กฎเหล็ก (01_SYSTEM_INSTRUCTIONS & 04_SPEC 5.7):
 * 1. ห้ามคัดลอกไฟล์ `.db` ที่เปิดอยู่โดยไม่ทำ WAL Checkpoint เด็ดขาด
 * 2. บังคับทำ `PRAGMA wal_checkpoint(TRUNCATE)` และตรวจ `busy == 0` ก่อนสร้าง Snapshot
 * 3. ถอดรหัสและตรวจ `AES-256-GCM Tag + Canonical Header AAD + SHA-256` ลงในไฟล์ `Staging` เท่านั้น
 * 4. ตรวจ `PRAGMA integrity_check`, `PRAGMA foreign_key_check`, `PRAGMA user_version` และตารางหลักใน `Staging` ก่อนเขียนทับ
 * 5. กู้คืนแบบ `Replace-Only` เท่านั้น (ห้าม Merge ข้อมูลปะปนกัน) และสำรองไฟล์เดิมไว้ที่ `.pre_restore_rollback` เพื่อย้อนกลับอัตโนมัติหากการสลับไฟล์ล้มเหลว
 */
class OfflineBackupAndRestoreManager(
    private val port: BackupDatabasePort
) {

    /**
     * ส่งออกไฟล์สำรองข้อมูล (.1pbackup) หลังจากทำ WAL Checkpoint สำเร็จ
     */
    suspend fun exportConsistentAuthenticatedBackup(
        passphrase: CharArray,
        outputStream: OutputStream,
        nowEpochMs: Long,
        noteTh: String = "สำรองฐานข้อมูลออฟไลน์แบบยืนยันความถูกต้อง (WAL Checkpoint + AES-256-GCM)",
        customArchiveId: String? = null
    ): BackupExportResult {
        var snapshotTempFile: File? = null
        try {
            if (passphrase.size < BackupDesignSpec.MIN_PASSPHRASE_LENGTH_CHARS) {
                return BackupExportResult.Rejected(
                    errorCode = BackupRestoreErrorCode.PASSPHRASE_TOO_SHORT,
                    messageTh = BackupRestoreErrorCode.PASSPHRASE_TOO_SHORT.thaiDescription
                )
            }

            val walResult = port.performWalCheckpointTruncate()
            if (!walResult.isConsistentAndComplete) {
                return BackupExportResult.Rejected(
                    errorCode = BackupRestoreErrorCode.WAL_CHECKPOINT_BUSY,
                    messageTh = "${BackupRestoreErrorCode.WAL_CHECKPOINT_BUSY.thaiDescription} (busy=${walResult.busy})"
                )
            }

            val activeDbFile = port.getActiveDatabaseFile()
            if (!activeDbFile.exists() || activeDbFile.length() == 0L) {
                return BackupExportResult.Rejected(
                    errorCode = BackupRestoreErrorCode.SQLITE_INTEGRITY_CHECK_FAILED,
                    messageTh = "ไม่พบไฟล์ฐานข้อมูลหลักสำหรับสำรองข้อมูล"
                )
            }

            val availableBytes = port.getAvailableStorageBytes()
            val requiredBytes = BackupArchiveCryptoEngine.computeRequiredAvailableStorageBytes(activeDbFile.length())
            if (availableBytes < requiredBytes) {
                return BackupExportResult.Rejected(
                    errorCode = BackupRestoreErrorCode.INSUFFICIENT_STORAGE_SPACE,
                    messageTh = BackupRestoreErrorCode.INSUFFICIENT_STORAGE_SPACE.thaiDescription
                )
            }

            val stagingDir = port.getStagingDirectory()
            stagingDir.mkdirs()
            val archiveId = customArchiveId ?: "bk-${nowEpochMs}-${UUID.randomUUID().toString().take(8)}"
            snapshotTempFile = File(stagingDir, "snapshot_${archiveId}.db")

            copyFileWithSync(activeDbFile, snapshotTempFile)
            val snapshotBytes = snapshotTempFile.readBytes()

            val writeOutcome = BackupArchiveCryptoEngine.writeAuthenticatedArchive(
                snapshotDatabaseBytes = snapshotBytes,
                passphrase = passphrase,
                archiveId = archiveId,
                createdAtEpochMs = nowEpochMs,
                walCheckpointVerified = true,
                availableStorageBytes = availableBytes,
                outputStream = outputStream,
                roomSchemaVersion = BackupDesignSpec.REQUIRED_ROOM_SCHEMA_VERSION
            )

            return when (writeOutcome) {
                is BackupArchiveCryptoEngine.ArchiveWriteOutcome.Rejected -> {
                    BackupExportResult.Rejected(
                        errorCode = writeOutcome.errorCode,
                        messageTh = writeOutcome.detailTh
                    )
                }

                is BackupArchiveCryptoEngine.ArchiveWriteOutcome.Success -> {
                    val metadata = BackupMetadataEntity(
                        archiveId = archiveId,
                        schemaVersion = writeOutcome.header.roomSchemaVersion,
                        archiveFormatVersion = writeOutcome.header.archiveFormatVersion,
                        walCheckpointCompleted = true,
                        payloadSha256 = writeOutcome.header.payloadSha256Hex,
                        actionType = "EXPORT_AUTHENTICATED_BACKUP",
                        noteTh = noteTh.ifBlank { "สำรองข้อมูลสำเร็จ" },
                        createdAtEpochMs = nowEpochMs
                    )
                    port.reopenDatabaseAndRecordMetadata(
                        metadata = metadata,
                        auditSummaryTh = "ส่งออกไฟล์สำรองข้อมูลรหัส $archiveId สำเร็จ (WAL Checkpoint=true, SHA256=${writeOutcome.header.payloadSha256Hex.take(12)}...)"
                    )
                    BackupExportResult.Success(
                        archiveId = archiveId,
                        header = writeOutcome.header,
                        totalBytesWritten = writeOutcome.totalArchiveBytesWritten,
                        metadataRecorded = metadata
                    )
                }
            }
        } finally {
            passphrase.fill('\u0000')
            snapshotTempFile?.let { file ->
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }

    /**
     * ขั้นตอนที่ 1 ของการกู้คืน:
     * ถอดรหัสไฟล์สำรองลงในพื้นที่ Staging + ตรวจ AES-GCM Tag (AAD) + ตรวจ SHA-256 + ตรวจ SQLite PRAGMA integrity_check & foreign_key_check
     * ยังไม่เขียนทับฐานข้อมูลจริงจนกว่าผู้ใช้จะยืนยันในขั้นตอนที่ 2
     */
    fun stageAndValidateArchive(
        inputStream: InputStream,
        passphrase: CharArray
    ): StagingPreviewResult {
        val stagingDir = port.getStagingDirectory()
        stagingDir.mkdirs()
        val tokenId = "stg-${UUID.randomUUID().toString().take(8)}"
        val stagingFile = File(stagingDir, "restore_candidate_${tokenId}.db")

        try {
            val readOutcome = BackupArchiveCryptoEngine.readAndAuthenticateArchiveToStaging(
                inputStream = inputStream,
                passphrase = passphrase,
                stagingFile = stagingFile,
                availableStorageBytes = port.getAvailableStorageBytes(),
                requiredRoomSchemaVersion = BackupDesignSpec.REQUIRED_ROOM_SCHEMA_VERSION
            )

            when (readOutcome) {
                is BackupArchiveCryptoEngine.ArchiveReadToStagingOutcome.Rejected -> {
                    if (stagingFile.exists()) {
                        stagingFile.delete()
                    }
                    return StagingPreviewResult.Rejected(
                        errorCode = readOutcome.errorCode,
                        messageTh = readOutcome.detailTh
                    )
                }

                is BackupArchiveCryptoEngine.ArchiveReadToStagingOutcome.Success -> {
                    val sqliteReport = port.validateStagingSqliteFile(stagingFile)
                    if (!sqliteReport.isValid) {
                        if (stagingFile.exists()) {
                            stagingFile.delete()
                        }
                        val detail = buildString {
                            append(BackupRestoreErrorCode.SQLITE_INTEGRITY_CHECK_FAILED.thaiDescription)
                            append(" (integrity=${sqliteReport.integrityCheckResult}, fkViolations=${sqliteReport.foreignKeyViolationCount}, schema=${sqliteReport.userSchemaVersion}")
                            if (sqliteReport.missingCoreTables.isNotEmpty()) {
                                append(", missingTables=${sqliteReport.missingCoreTables.joinToString(",")}")
                            }
                            append(")")
                        }
                        return StagingPreviewResult.Rejected(
                            errorCode = BackupRestoreErrorCode.SQLITE_INTEGRITY_CHECK_FAILED,
                            messageTh = detail
                        )
                    }

                    return StagingPreviewResult.ReadyForReplaceConfirmation(
                        preview = StagingRestorePreview(
                            stagingTokenId = tokenId,
                            header = readOutcome.header,
                            stagingDatabaseFile = stagingFile,
                            validationReport = sqliteReport
                        )
                    )
                }
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }

    /**
     * ยกเลิกไฟล์ที่พักไว้ใน Staging หากผู้ใช้กดยกเลิกการกู้คืน
     */
    fun discardStagedPreview(preview: StagingRestorePreview) {
        if (preview.stagingDatabaseFile.exists()) {
            preview.stagingDatabaseFile.delete()
        }
    }

    /**
     * ขั้นตอนที่ 2 ของการกู้คืน:
     * สลับไฟล์ฐานข้อมูลแบบ Replace-Only (ไม่ผสานข้อมูลปะปนกัน) พร้อมเก็บสำเนาฉุกเฉิน `.pre_restore_rollback`
     * หากเกิดข้อผิดพลาดระหว่างสลับไฟล์ ระบบจะนำ `.pre_restore_rollback` กลับมาแทนที่อัตโนมัติ
     */
    suspend fun executeValidatedReplaceOnlyRestore(
        stagedPreview: StagingRestorePreview,
        confirmReplaceOnlyOverwrite: Boolean,
        nowEpochMs: Long,
        noteTh: String = "กู้คืนฐานข้อมูลแบบ Replace-Only จากไฟล์สำรองที่ผ่านการตรวจ Staging แล้ว"
    ): RestoreExecutionResult {
        if (!confirmReplaceOnlyOverwrite) {
            return RestoreExecutionResult.Rejected(
                errorCode = BackupRestoreErrorCode.SQLITE_INTEGRITY_CHECK_FAILED,
                messageTh = "ต้องยืนยันการเขียนทับข้อมูลเดิมทั้งหมด (Replace-Only) ก่อนดำเนินการกู้คืน",
                rolledBackToPreviousDb = false
            )
        }

        val stagingFile = stagedPreview.stagingDatabaseFile
        if (!stagingFile.exists() || stagingFile.length() != stagedPreview.header.payloadUnencryptedSizeBytes) {
            return RestoreExecutionResult.Rejected(
                errorCode = BackupRestoreErrorCode.SQLITE_INTEGRITY_CHECK_FAILED,
                messageTh = "ไม่พบไฟล์ฐานข้อมูลใน Staging หรือขนาดไฟล์เปลี่ยนแปลง กรุณาตรวจสอบไฟล์สำรองใหม่อีกครั้ง",
                rolledBackToPreviousDb = false
            )
        }

        // ตรวจซ้ำอีกครั้งก่อนปิดฐานข้อมูลจริง
        val recheckSha256 = BackupArchiveCryptoEngine.sha256Hex(stagingFile.readBytes())
        if (!recheckSha256.equals(stagedPreview.header.payloadSha256Hex, ignoreCase = true)) {
            stagingFile.delete()
            return RestoreExecutionResult.Rejected(
                errorCode = BackupRestoreErrorCode.PAYLOAD_HASH_MISMATCH,
                messageTh = BackupRestoreErrorCode.PAYLOAD_HASH_MISMATCH.thaiDescription,
                rolledBackToPreviousDb = false
            )
        }

        val recheckReport = port.validateStagingSqliteFile(stagingFile)
        if (!recheckReport.isValid) {
            stagingFile.delete()
            return RestoreExecutionResult.Rejected(
                errorCode = BackupRestoreErrorCode.SQLITE_INTEGRITY_CHECK_FAILED,
                messageTh = BackupRestoreErrorCode.SQLITE_INTEGRITY_CHECK_FAILED.thaiDescription,
                rolledBackToPreviousDb = false
            )
        }

        val activeDbFile = port.getActiveDatabaseFile()
        val activeWalFile = File(activeDbFile.parentFile, "${activeDbFile.name}-wal")
        val activeShmFile = File(activeDbFile.parentFile, "${activeDbFile.name}-shm")
        val rollbackFile = File(activeDbFile.parentFile, "${activeDbFile.name}.pre_restore_rollback")

        // ทำ WAL Checkpoint และปิดการเชื่อมต่อฐานข้อมูลปัจจุบันก่อนสลับไฟล์
        try {
            port.performWalCheckpointTruncate()
        } catch (_: Exception) {
            // หากฐานข้อมูลเดิมยังไม่เปิดหรือไม่มี WAL ให้ดำเนินการปิดต่อได้
        }
        port.closeActiveDatabaseForSwap()

        var rollbackCreated = false
        try {
            activeDbFile.parentFile?.mkdirs()
            if (activeDbFile.exists()) {
                copyFileWithSync(activeDbFile, rollbackFile)
                rollbackCreated = true
            }

            // ลบไฟล์ -wal และ -shm ของฐานข้อมูลเดิมเพื่อไม่ให้เฟรมเก่าปะปนกับฐานข้อมูลที่กู้คืนใหม่
            if (activeWalFile.exists()) activeWalFile.delete()
            if (activeShmFile.exists()) activeShmFile.delete()

            // เขียนทับฐานข้อมูลหลักด้วยไฟล์ Staging ที่ตรวจผ่านแล้วเท่านั้น (Replace-Only, Never Merge)
            copyFileWithSync(stagingFile, activeDbFile)
            stagingFile.delete()

            val metadata = BackupMetadataEntity(
                archiveId = stagedPreview.header.archiveId,
                schemaVersion = stagedPreview.header.roomSchemaVersion,
                archiveFormatVersion = stagedPreview.header.archiveFormatVersion,
                walCheckpointCompleted = true,
                payloadSha256 = stagedPreview.header.payloadSha256Hex,
                actionType = "RESTORE_REPLACE_ONLY_COMPLETED",
                noteTh = noteTh.ifBlank { "กู้คืนแบบ Replace-Only สำเร็จ" },
                createdAtEpochMs = nowEpochMs
            )

            port.reopenDatabaseAndRecordMetadata(
                metadata = metadata,
                auditSummaryTh = "กู้คืนฐานข้อมูลแบบ Replace-Only จากไฟล์สำรอง ${stagedPreview.header.archiveId} สำเร็จ (งวด=${recheckReport.previewRoundCode}, บิล=${recheckReport.previewBillCount})"
            )

            return RestoreExecutionResult.Success(
                archiveId = stagedPreview.header.archiveId,
                restoredRoundCode = recheckReport.previewRoundCode,
                restoredBillCount = recheckReport.previewBillCount,
                rollbackBackupFilePreserved = rollbackCreated && rollbackFile.exists(),
                metadataRecorded = metadata
            )
        } catch (swapError: Exception) {
            // หากเกิดข้อผิดพลาดระหว่างการสลับไฟล์หรือเปิดฐานข้อมูลใหม่ ให้ย้อนกลับจากไฟล์ .pre_restore_rollback ทันที
            var rolledBack = false
            if (rollbackCreated && rollbackFile.exists()) {
                try {
                    if (activeWalFile.exists()) activeWalFile.delete()
                    if (activeShmFile.exists()) activeShmFile.delete()
                    copyFileWithSync(rollbackFile, activeDbFile)
                    rolledBack = true
                } catch (_: Exception) {
                    rolledBack = false
                }
            }
            return RestoreExecutionResult.Rejected(
                errorCode = BackupRestoreErrorCode.SQLITE_INTEGRITY_CHECK_FAILED,
                messageTh = "เกิดข้อผิดพลาดขณะสลับไฟล์ฐานข้อมูล ระบบได้กู้คืนฐานข้อมูลเดิมก่อนเริ่มทำรายการกลับคืนแล้ว",
                rolledBackToPreviousDb = rolledBack
            )
        }
    }

    companion object {
        val REQUIRED_CORE_TABLES: List<String> = listOf(
            "rounds",
            "policy_versions",
            "bills",
            "bill_revisions",
            "bill_number_side_lines",
            "number_side_counters",
            "audit_logs",
            "backup_metadata"
        )

        private fun copyFileWithSync(source: File, target: File) {
            target.parentFile?.mkdirs()
            FileInputStream(source).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output, bufferSize = 16_384)
                    output.flush()
                    output.fd.sync()
                }
            }
        }
    }
}

/**
 * พอร์ตมาตรฐานสำหรับใช้งานบนเครื่อง Android จริง ร่วมกับ Room (`OneProjectDatabase`) และ `SQLiteDatabase`
 */
class AndroidRoomBackupDatabasePort(
    private val appContext: Context
) : BackupDatabasePort {

    override fun getActiveDatabaseFile(): File {
        return appContext.getDatabasePath(OneProjectDatabase.DATABASE_NAME)
    }

    override fun getStagingDirectory(): File {
        return File(appContext.filesDir, "backup_staging").also { it.mkdirs() }
    }

    override fun getAvailableStorageBytes(): Long {
        val statFs = StatFs(appContext.filesDir.absolutePath)
        return statFs.availableBytes
    }

    override suspend fun performWalCheckpointTruncate(): WalCheckpointResult {
        val db = OneProjectDatabase.getInstance(appContext).openHelper.writableDatabase
        db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            if (cursor.moveToFirst()) {
                val busy = cursor.getInt(0)
                val logFrames = if (cursor.columnCount > 1) cursor.getInt(1) else 0
                val checkpointedFrames = if (cursor.columnCount > 2) cursor.getInt(2) else 0
                return WalCheckpointResult(
                    busy = busy,
                    logFrames = logFrames,
                    checkpointedFrames = checkpointedFrames
                )
            }
        }
        return WalCheckpointResult(busy = 0, logFrames = 0, checkpointedFrames = 0)
    }

    override fun validateStagingSqliteFile(stagingFile: File): StagingSqliteValidationReport {
        if (!stagingFile.exists() || stagingFile.length() == 0L) {
            return StagingSqliteValidationReport(
                isValid = false,
                integrityCheckResult = "missing_staging_file",
                foreignKeyViolationCount = -1,
                userSchemaVersion = -1,
                missingCoreTables = OfflineBackupAndRestoreManager.REQUIRED_CORE_TABLES,
                previewRoundCode = "-",
                previewBillCount = 0,
                previewAuditCount = 0
            )
        }

        return try {
            SQLiteDatabase.openDatabase(
                stagingFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { sqliteDb ->
                val integrityResult = sqliteDb.rawQuery("PRAGMA integrity_check", null).use { c ->
                    if (c.moveToFirst()) c.getString(0) ?: "unknown" else "empty"
                }

                val fkViolationCount = sqliteDb.rawQuery("PRAGMA foreign_key_check", null).use { c ->
                    c.count
                }

                val userVersion = sqliteDb.rawQuery("PRAGMA user_version", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else -1
                }

                val existingTables = mutableSetOf<String>()
                sqliteDb.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table'",
                    null
                ).use { c ->
                    while (c.moveToNext()) {
                        c.getString(0)?.let { existingTables.add(it) }
                    }
                }

                val missingTables = OfflineBackupAndRestoreManager.REQUIRED_CORE_TABLES.filter { table ->
                    table !in existingTables
                }

                val previewRoundCode = if ("rounds" in existingTables) {
                    sqliteDb.rawQuery(
                        "SELECT round_code FROM rounds ORDER BY created_at_epoch_ms DESC LIMIT 1",
                        null
                    ).use { c ->
                        if (c.moveToFirst()) c.getString(0) ?: "ไม่มีงวด" else "ไม่มีงวด"
                    }
                } else {
                    "-"
                }

                val previewBillCount = if ("bills" in existingTables) {
                    sqliteDb.rawQuery("SELECT COUNT(*) FROM bills", null).use { c ->
                        if (c.moveToFirst()) c.getInt(0) else 0
                    }
                } else {
                    0
                }

                val previewAuditCount = if ("audit_logs" in existingTables) {
                    sqliteDb.rawQuery("SELECT COUNT(*) FROM audit_logs", null).use { c ->
                        if (c.moveToFirst()) c.getInt(0) else 0
                    }
                } else {
                    0
                }

                val isValid = integrityResult.equals("ok", ignoreCase = true) &&
                    fkViolationCount == 0 &&
                    userVersion == BackupDesignSpec.REQUIRED_ROOM_SCHEMA_VERSION &&
                    missingTables.isEmpty()

                StagingSqliteValidationReport(
                    isValid = isValid,
                    integrityCheckResult = integrityResult,
                    foreignKeyViolationCount = fkViolationCount,
                    userSchemaVersion = userVersion,
                    missingCoreTables = missingTables,
                    previewRoundCode = previewRoundCode,
                    previewBillCount = previewBillCount,
                    previewAuditCount = previewAuditCount
                )
            }
        } catch (e: Exception) {
            StagingSqliteValidationReport(
                isValid = false,
                integrityCheckResult = "exception:${e.javaClass.simpleName}",
                foreignKeyViolationCount = -1,
                userSchemaVersion = -1,
                missingCoreTables = OfflineBackupAndRestoreManager.REQUIRED_CORE_TABLES,
                previewRoundCode = "-",
                previewBillCount = 0,
                previewAuditCount = 0
            )
        }
    }

    override suspend fun closeActiveDatabaseForSwap() {
        OneProjectDatabase.closeAndResetInstance()
    }

    override suspend fun reopenDatabaseAndRecordMetadata(
        metadata: BackupMetadataEntity,
        auditSummaryTh: String
    ) {
        val reopenedDb = OneProjectDatabase.getInstance(appContext)
        val bootstrapDao = reopenedDb.bootstrapDao()
        bootstrapDao.insertBackupMetadata(metadata)
        bootstrapDao.insertAuditLog(
            AuditLogEntity(
                eventType = metadata.actionType,
                actorRole = "LOCAL_ADMIN",
                roundId = null,
                summaryTh = auditSummaryTh,
                createdAtEpochMs = metadata.createdAtEpochMs
            )
        )
    }
}
