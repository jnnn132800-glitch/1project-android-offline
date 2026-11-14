package com.oneproject.offline.data.backup

import com.oneproject.offline.core.security.BackupArchiveCryptoEngine
import com.oneproject.offline.core.security.BackupDesignSpec
import com.oneproject.offline.core.security.BackupRestoreErrorCode
import com.oneproject.offline.data.local.dao.BootstrapDao
import com.oneproject.offline.data.local.entity.AuditLogEntity
import com.oneproject.offline.data.local.entity.BackupMetadataEntity
import com.oneproject.offline.data.local.entity.PolicyVersionEntity
import com.oneproject.offline.data.local.entity.RoundEntity
import com.oneproject.offline.data.repository.OfflineBootstrapRepository
import com.oneproject.offline.ui.backup.BackupAndRestoreWorkflowController
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ชุดทดสอบสำหรับ Phase 06B (Backup & Restore Implementation)
 * ครอบคลุมตามคำสั่ง Phase 06B:
 * 1. ส่งออกไฟล์สำรองข้อมูล (.1pbackup) หลังทำ WAL Checkpoint และกู้คืนข้ามเครื่อง (Cross-Device Restore) โดยไม่พึ่งพา Android Keystore ของเครื่องเก่า
 * 2. ทดสอบรหัสผ่านไม่ถูกต้อง (Wrong Password -> WRONG_PASSWORD_OR_CORRUPT_ARCHIVE) และยืนยันว่าฐานข้อมูลเดิมไม่ถูกแตะต้อง
 * 3. ทดสอบไฟล์สำรองเสียหายหรือถูกแก้ไขส่วนหัว/เนื้อหา (Corrupt / Tampered Archive -> AAD & GCM Tag Verification Failed)
 * 4. ทดสอบพื้นที่ว่างไม่เพียงพอ (Low Storage Guard -> INSUFFICIENT_STORAGE_SPACE) ทั้งขา Export และขา Restore
 * 5. ทดสอบการตรวจสอบใน Staging, การกู้คืนแบบ Replace-Only (ไม่ Merge ข้อมูลปะปนกัน), และการทำ Rollback อัตโนมัติหากการสลับไฟล์ล้มเหลว
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Phase06BBackupRestoreTest {

    @Test
    fun exportAndCrossDeviceReplaceOnlyRestore_preservesExactSnapshotWithoutDeviceKeystore_andReplacesNeverMerges() = runTest {
        val tempRoot = Files.createTempDirectory("phase06b_crossdevice").toFile()
        try {
            // เครื่องต้นทาง (Device A): มีฐานข้อมูลที่มี 12 บิลในงวด RND-2026-06B
            val deviceADir = File(tempRoot, "device_a").also { it.mkdirs() }
            val deviceASourcePayload = "SQLITE_SNAPSHOT_DEVICE_A|ROUND=RND-2026-06B|BILLS=12|COUNTER_25_TOP=45000".toByteArray(StandardCharsets.UTF_8)
            val deviceAPort = FakeBackupDatabasePort(
                rootDir = deviceADir,
                initialDbBytes = deviceASourcePayload,
                simulatedRoundCode = "RND-2026-06B",
                simulatedBillCount = 12,
                simulatedAuditCount = 34
            )
            val deviceAManager = OfflineBackupAndRestoreManager(deviceAPort)

            val archiveBuffer = ByteArrayOutputStream()
            val exportPassphrase = "CorrectHorseBatteryStaple#2026".toCharArray()
            val exportResult = deviceAManager.exportConsistentAuthenticatedBackup(
                passphrase = exportPassphrase,
                outputStream = archiveBuffer,
                nowEpochMs = 1_765_000_000_000L,
                noteTh = "สำรองก่อนย้ายเครื่องใหม่",
                customArchiveId = "bk-device-a-001"
            )

            assertTrue("Export ต้องสำเร็จ", exportResult is BackupExportResult.Success)
            val exportSuccess = exportResult as BackupExportResult.Success
            assertEquals("bk-device-a-001", exportSuccess.archiveId)
            assertEquals(1, deviceAPort.recordedMetadata.size)
            assertEquals("EXPORT_AUTHENTICATED_BACKUP", deviceAPort.recordedMetadata.first().actionType)

            // ยืนยันว่ารหัสผ่านใน CharArray ถูกล้างเป็น '\u0000' หลังจบการทำงาน
            assertTrue(exportPassphrase.all { it == '\u0000' })

            // เครื่องปลายทางใหม่ (Device B): มีข้อมูลเก่าคนละงวดอยู่ ("OLD_DEVICE_B_DATA") และไม่มี Keystore ร่วมกับเครื่อง A
            val deviceBDir = File(tempRoot, "device_b").also { it.mkdirs() }
            val deviceBOldPayload = "SQLITE_SNAPSHOT_DEVICE_B_OLD|ROUND=DEMO-OLD|BILLS=2".toByteArray(StandardCharsets.UTF_8)
            val deviceBPort = FakeBackupDatabasePort(
                rootDir = deviceBDir,
                initialDbBytes = deviceBOldPayload,
                simulatedRoundCode = "RND-2026-06B",
                simulatedBillCount = 12,
                simulatedAuditCount = 34
            )
            val deviceBManager = OfflineBackupAndRestoreManager(deviceBPort)

            // ขั้นตอนที่ 1 บนเครื่อง B: ถอดรหัสและตรวจความสมบูรณ์ใน Staging
            val stageResult = deviceBManager.stageAndValidateArchive(
                inputStream = ByteArrayInputStream(archiveBuffer.toByteArray()),
                passphrase = "CorrectHorseBatteryStaple#2026".toCharArray()
            )
            assertTrue("Stage & Validate ต้องผ่านครบทุกด่าน", stageResult is StagingPreviewResult.ReadyForReplaceConfirmation)
            val stagedPreview = (stageResult as StagingPreviewResult.ReadyForReplaceConfirmation).preview
            assertEquals("bk-device-a-001", stagedPreview.header.archiveId)
            assertEquals("ok", stagedPreview.validationReport.integrityCheckResult)
            assertEquals(0, stagedPreview.validationReport.foreignKeyViolationCount)
            assertEquals("RND-2026-06B", stagedPreview.validationReport.previewRoundCode)
            assertEquals(12, stagedPreview.validationReport.previewBillCount)

            // ขณะยังอยู่ใน Staging ฐานข้อมูลจริงของเครื่อง B ต้องยังไม่ถูกเปลี่ยนแปลง
            assertArrayEquals(deviceBOldPayload, deviceBPort.getActiveDatabaseFile().readBytes())

            // ขั้นตอนที่ 2 บนเครื่อง B: ยืนยัน Replace-Only Restore
            val restoreResult = deviceBManager.executeValidatedReplaceOnlyRestore(
                stagedPreview = stagedPreview,
                confirmReplaceOnlyOverwrite = true,
                nowEpochMs = 1_765_000_060_000L,
                noteTh = "กู้คืนข้ามเครื่องจาก Device A"
            )
            assertTrue("Replace-Only Restore ต้องสำเร็จ", restoreResult is RestoreExecutionResult.Success)
            val restoreSuccess = restoreResult as RestoreExecutionResult.Success
            assertEquals("RND-2026-06B", restoreSuccess.restoredRoundCode)
            assertEquals(12, restoreSuccess.restoredBillCount)
            assertTrue("ต้องเก็บไฟล์สำรองก่อนทับ (.pre_restore_rollback) ไว้", restoreSuccess.rollbackBackupFilePreserved)

            // ฐานข้อมูลบนเครื่อง B ต้องถูกแทนที่ด้วย Snapshot ของเครื่อง A แบบ 100% (Replace-Only, ไม่ Merge)
            assertArrayEquals(deviceASourcePayload, deviceBPort.getActiveDatabaseFile().readBytes())
            assertFalse("ไฟล์ใน Staging ต้องถูกลบหลังสลับไฟล์สำเร็จ", stagedPreview.stagingDatabaseFile.exists())
            assertEquals("RESTORE_REPLACE_ONLY_COMPLETED", deviceBPort.recordedMetadata.last().actionType)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun wrongPassword_andCorruptTamperedArchive_failAuthentication_andNeverTouchActiveDatabase() = runTest {
        val tempRoot = Files.createTempDirectory("phase06b_wrongpass_corrupt").toFile()
        try {
            val activeBytes = "AUTHORITATIVE_DB_MUST_NOT_CHANGE".toByteArray(StandardCharsets.UTF_8)
            val port = FakeBackupDatabasePort(
                rootDir = tempRoot,
                initialDbBytes = activeBytes,
                simulatedRoundCode = "RND-SAFE-01",
                simulatedBillCount = 5,
                simulatedAuditCount = 10
            )
            val manager = OfflineBackupAndRestoreManager(port)

            val out = ByteArrayOutputStream()
            val exportRes = manager.exportConsistentAuthenticatedBackup(
                passphrase = "ValidPassphrase!99".toCharArray(),
                outputStream = out,
                nowEpochMs = 1_765_000_100_000L
            )
            assertTrue(exportRes is BackupExportResult.Success)
            val validArchiveBytes = out.toByteArray()

            // กรณีที่ 1: รหัสผ่านผิด (Wrong Password)
            val wrongPassStage = manager.stageAndValidateArchive(
                inputStream = ByteArrayInputStream(validArchiveBytes),
                passphrase = "WrongPassphrase!00".toCharArray()
            )
            assertTrue(wrongPassStage is StagingPreviewResult.Rejected)
            assertEquals(
                BackupRestoreErrorCode.WRONG_PASSWORD_OR_CORRUPT_ARCHIVE,
                (wrongPassStage as StagingPreviewResult.Rejected).errorCode
            )
            assertArrayEquals(activeBytes, port.getActiveDatabaseFile().readBytes())

            // กรณีที่ 2: ไฟล์สำรองเสียหาย/ถูกแก้ไขบิตใน Ciphertext (Corrupt Archive)
            val corruptedCipherBytes = validArchiveBytes.copyOf()
            corruptedCipherBytes[corruptedCipherBytes.size - 5] =
                (corruptedCipherBytes[corruptedCipherBytes.size - 5].toInt() xor 0x55).toByte()

            val corruptStage = manager.stageAndValidateArchive(
                inputStream = ByteArrayInputStream(corruptedCipherBytes),
                passphrase = "ValidPassphrase!99".toCharArray()
            )
            assertTrue(corruptStage is StagingPreviewResult.Rejected)
            assertEquals(
                BackupRestoreErrorCode.WRONG_PASSWORD_OR_CORRUPT_ARCHIVE,
                (corruptStage as StagingPreviewResult.Rejected).errorCode
            )
            assertArrayEquals(activeBytes, port.getActiveDatabaseFile().readBytes())

            // กรณีที่ 3: Magic Header ไม่ถูกต้อง
            val invalidHeaderBytes = "NOT_1PRJ_GARBAGE_DATA".toByteArray(StandardCharsets.UTF_8)
            val invalidHeaderStage = manager.stageAndValidateArchive(
                inputStream = ByteArrayInputStream(invalidHeaderBytes),
                passphrase = "ValidPassphrase!99".toCharArray()
            )
            assertTrue(invalidHeaderStage is StagingPreviewResult.Rejected)
            assertEquals(
                BackupRestoreErrorCode.INVALID_ARCHIVE_HEADER,
                (invalidHeaderStage as StagingPreviewResult.Rejected).errorCode
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun lowStorage_walCheckpointBusy_andSwapFailureRollback_enforceSafetyGuards() = runTest {
        val tempRoot = Files.createTempDirectory("phase06b_guards").toFile()
        try {
            val originalDbBytes = "ORIGINAL_ACTIVE_DB_BEFORE_FAULT".toByteArray(StandardCharsets.UTF_8)
            val port = FakeBackupDatabasePort(
                rootDir = tempRoot,
                initialDbBytes = originalDbBytes,
                simulatedRoundCode = "RND-GUARD-01",
                simulatedBillCount = 3,
                simulatedAuditCount = 7
            )
            val manager = OfflineBackupAndRestoreManager(port)

            // 1. ทดสอบ WAL Checkpoint Busy -> ห้ามสำรองข้อมูลทั้งที่ WAL ยังรวมไม่เสร็จ
            port.simulatedWalBusy = 1
            val walBusyExport = manager.exportConsistentAuthenticatedBackup(
                passphrase = "StrongPassphrase#123".toCharArray(),
                outputStream = ByteArrayOutputStream(),
                nowEpochMs = 1_765_000_200_000L
            )
            assertTrue(walBusyExport is BackupExportResult.Rejected)
            assertEquals(
                BackupRestoreErrorCode.WAL_CHECKPOINT_BUSY,
                (walBusyExport as BackupExportResult.Rejected).errorCode
            )

            // คืนค่า WAL ปกติแล้วสร้างไฟล์สำรองที่ถูกต้องไว้ทดสอบขา Restore
            port.simulatedWalBusy = 0
            val validArchiveOut = ByteArrayOutputStream()
            val okExport = manager.exportConsistentAuthenticatedBackup(
                passphrase = "StrongPassphrase#123".toCharArray(),
                outputStream = validArchiveOut,
                nowEpochMs = 1_765_000_210_000L
            )
            assertTrue(okExport is BackupExportResult.Success)

            // 2. ทดสอบพื้นที่ว่างไม่พอ (Low Storage Guard)
            port.simulatedAvailableStorageBytes = 1024L // เหลือเพียง 1 KB (ต่ำกว่า 3x + 32MB)
            val lowStorageExport = manager.exportConsistentAuthenticatedBackup(
                passphrase = "StrongPassphrase#123".toCharArray(),
                outputStream = ByteArrayOutputStream(),
                nowEpochMs = 1_765_000_220_000L
            )
            assertTrue(lowStorageExport is BackupExportResult.Rejected)
            assertEquals(
                BackupRestoreErrorCode.INSUFFICIENT_STORAGE_SPACE,
                (lowStorageExport as BackupExportResult.Rejected).errorCode
            )

            val lowStorageStage = manager.stageAndValidateArchive(
                inputStream = ByteArrayInputStream(validArchiveOut.toByteArray()),
                passphrase = "StrongPassphrase#123".toCharArray()
            )
            assertTrue(lowStorageStage is StagingPreviewResult.Rejected)
            assertEquals(
                BackupRestoreErrorCode.INSUFFICIENT_STORAGE_SPACE,
                (lowStorageStage as StagingPreviewResult.Rejected).errorCode
            )

            // 3. ทดสอบ SQLite Integrity Check ล้มเหลวใน Staging
            port.simulatedAvailableStorageBytes = 500_000_000L
            port.simulatedIntegrityCheckResult = "malformed database schema"
            val failedIntegrityStage = manager.stageAndValidateArchive(
                inputStream = ByteArrayInputStream(validArchiveOut.toByteArray()),
                passphrase = "StrongPassphrase#123".toCharArray()
            )
            assertTrue(failedIntegrityStage is StagingPreviewResult.Rejected)
            assertEquals(
                BackupRestoreErrorCode.SQLITE_INTEGRITY_CHECK_FAILED,
                (failedIntegrityStage as StagingPreviewResult.Rejected).errorCode
            )

            // 4. ทดสอบ Automatic Rollback จากไฟล์ .pre_restore_rollback เมื่อเกิดข้อผิดพลาดตอนเปิดฐานข้อมูลหลังสลับไฟล์
            port.simulatedIntegrityCheckResult = "ok"
            val validStage = manager.stageAndValidateArchive(
                inputStream = ByteArrayInputStream(validArchiveOut.toByteArray()),
                passphrase = "StrongPassphrase#123".toCharArray()
            ) as StagingPreviewResult.ReadyForReplaceConfirmation

            // เปลี่ยนข้อมูลในฐานข้อมูลหลักปัจจุบันเพื่อพิสูจน์ว่าเมื่อเกิด Fault ตอน Reopen ระบบจะย้อนกลับเป็นข้อมูลชุดนี้
            val currentActiveBeforeRestore = "CURRENT_ACTIVE_DB_TO_BE_ROLLED_BACK".toByteArray(StandardCharsets.UTF_8)
            port.getActiveDatabaseFile().writeBytes(currentActiveBeforeRestore)
            port.throwOnReopen = true

            val faultRestore = manager.executeValidatedReplaceOnlyRestore(
                stagedPreview = validStage.preview,
                confirmReplaceOnlyOverwrite = true,
                nowEpochMs = 1_765_000_250_000L
            )
            assertTrue(faultRestore is RestoreExecutionResult.Rejected)
            val rejectedFault = faultRestore as RestoreExecutionResult.Rejected
            assertTrue("ต้องทำการ Rollback ฐานข้อมูลเดิมกลับมาอัตโนมัติ", rejectedFault.rolledBackToPreviousDb)
            assertArrayEquals(currentActiveBeforeRestore, port.getActiveDatabaseFile().readBytes())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun workflowController_enforcesTwoStepStagingAndConfirmationCheckbox() = runTest {
        val tempRoot = Files.createTempDirectory("phase06b_controller").toFile()
        try {
            val port = FakeBackupDatabasePort(
                rootDir = tempRoot,
                initialDbBytes = "CONTROLLER_DB_SNAPSHOT".toByteArray(StandardCharsets.UTF_8),
                simulatedRoundCode = "DEMO-2026-001",
                simulatedBillCount = 4,
                simulatedAuditCount = 9
            )
            val manager = OfflineBackupAndRestoreManager(port)
            val fakeBootstrapDao = FakeBootstrapDaoForBackup(port)
            val repo = OfflineBootstrapRepository(fakeBootstrapDao)
            val controller = BackupAndRestoreWorkflowController(
                backupManager = manager,
                bootstrapRepository = repo,
                scope = backgroundScope,
                clockEpochMsProvider = { 1_765_000_300_000L }
            )

            controller.updateExportPassphrase("MySecretBackup#06B")
            val archiveOut = ByteArrayOutputStream()
            val exportRes = controller.executeExportToStream(archiveOut)
            assertTrue(exportRes is BackupExportResult.Success)
            assertEquals("", controller.uiState.value.exportPassphraseInput)
            assertNotNull(controller.uiState.value.statusBannerTh)

            // ทดสอบนำเข้าผ่าน Controller
            controller.updateRestorePassphrase("MySecretBackup#06B")
            val stageRes = controller.executeStageAndValidateFromStream(
                ByteArrayInputStream(archiveOut.toByteArray())
            )
            assertTrue(stageRes is StagingPreviewResult.ReadyForReplaceConfirmation)
            assertNotNull(controller.uiState.value.stagedPreview)
            assertFalse(controller.uiState.value.replaceOnlyConfirmationChecked)

            // หากยังไม่ติ๊กยืนยัน Replace-Only ต้องไม่อนุญาตให้เขียนทับ
            val prematureRestore = controller.executeConfirmedReplaceOnlyRestore()
            assertTrue(prematureRestore is RestoreExecutionResult.Rejected)
            assertNotNull(controller.uiState.value.errorBannerTh)

            // ติ๊กยืนยัน Replace-Only แล้วสั่งกู้คืน
            controller.setReplaceOnlyConfirmationChecked(true)
            val confirmedRestore = controller.executeConfirmedReplaceOnlyRestore()
            assertTrue(confirmedRestore is RestoreExecutionResult.Success)
            assertNull(controller.uiState.value.stagedPreview)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private class FakeBackupDatabasePort(
        private val rootDir: File,
        initialDbBytes: ByteArray,
        private val simulatedRoundCode: String,
        private val simulatedBillCount: Int,
        private val simulatedAuditCount: Int
    ) : BackupDatabasePort {

        private val dbFile = File(rootDir, "oneproject_authoritative_offline.db").also {
            it.parentFile?.mkdirs()
            it.writeBytes(initialDbBytes)
        }
        private val stagingDir = File(rootDir, "backup_staging").also { it.mkdirs() }

        var simulatedAvailableStorageBytes: Long = 500_000_000L
        var simulatedWalBusy: Int = 0
        var simulatedIntegrityCheckResult: String = "ok"
        var simulatedFkViolations: Int = 0
        var simulatedSchemaVersion: Int = BackupDesignSpec.REQUIRED_ROOM_SCHEMA_VERSION
        var throwOnReopen: Boolean = false

        val recordedMetadata = mutableListOf<BackupMetadataEntity>()
        val recordedAudits = mutableListOf<String>()

        override fun getActiveDatabaseFile(): File = dbFile

        override fun getStagingDirectory(): File = stagingDir

        override fun getAvailableStorageBytes(): Long = simulatedAvailableStorageBytes

        override suspend fun performWalCheckpointTruncate(): WalCheckpointResult {
            return WalCheckpointResult(
                busy = simulatedWalBusy,
                logFrames = 4,
                checkpointedFrames = if (simulatedWalBusy == 0) 4 else 1
            )
        }

        override fun validateStagingSqliteFile(stagingFile: File): StagingSqliteValidationReport {
            val isValid = stagingFile.exists() &&
                stagingFile.length() > 0L &&
                simulatedIntegrityCheckResult == "ok" &&
                simulatedFkViolations == 0 &&
                simulatedSchemaVersion == BackupDesignSpec.REQUIRED_ROOM_SCHEMA_VERSION

            return StagingSqliteValidationReport(
                isValid = isValid,
                integrityCheckResult = simulatedIntegrityCheckResult,
                foreignKeyViolationCount = simulatedFkViolations,
                userSchemaVersion = simulatedSchemaVersion,
                missingCoreTables = emptyList(),
                previewRoundCode = simulatedRoundCode,
                previewBillCount = simulatedBillCount,
                previewAuditCount = simulatedAuditCount
            )
        }

        override suspend fun closeActiveDatabaseForSwap() {
            // จำลองการปิด Room Singleton
        }

        override suspend fun reopenDatabaseAndRecordMetadata(
            metadata: BackupMetadataEntity,
            auditSummaryTh: String
        ) {
            if (throwOnReopen) {
                throw IllegalStateException("Simulated database reopen failure")
            }
            recordedMetadata.add(0, metadata)
            recordedAudits.add(auditSummaryTh)
        }
    }

    private class FakeBootstrapDaoForBackup(
        private val port: FakeBackupDatabasePort
    ) : BootstrapDao {
        private val historyFlow = MutableStateFlow<List<BackupMetadataEntity>>(emptyList())

        override suspend fun countRounds(): Int = 1
        override fun observeLatestRound(): Flow<RoundEntity?> = MutableStateFlow(null)
        override fun observePolicyVersion(roundId: String, policyVersion: Long): Flow<PolicyVersionEntity?> =
            MutableStateFlow(null)
        override fun observeAuditCount(): Flow<Int> = MutableStateFlow(port.recordedAudits.size)
        override fun observeBackupHistory(): Flow<List<BackupMetadataEntity>> = historyFlow
        override suspend fun getBackupHistory(): List<BackupMetadataEntity> = port.recordedMetadata.toList()
        override suspend fun insertBackupMetadata(metadata: BackupMetadataEntity) {
            port.recordedMetadata.add(0, metadata)
            historyFlow.value = port.recordedMetadata.toList()
        }
        override suspend fun insertRound(round: RoundEntity) {}
        override suspend fun insertPolicyVersion(policyVersion: PolicyVersionEntity): Long = 1L
        override suspend fun insertAuditLog(auditLog: AuditLogEntity): Long = 1L
    }
}
