package com.oneproject.offline.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.oneproject.offline.data.local.entity.AuditLogEntity
import com.oneproject.offline.data.local.entity.BackupMetadataEntity
import com.oneproject.offline.data.local.entity.PolicyVersionEntity
import com.oneproject.offline.data.local.entity.RoundEntity
import com.oneproject.offline.data.local.entity.RoundStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface BootstrapDao {

    @Query("SELECT COUNT(*) FROM rounds")
    suspend fun countRounds(): Int

    @Query("SELECT * FROM rounds ORDER BY created_at_epoch_ms DESC LIMIT 1")
    fun observeLatestRound(): Flow<RoundEntity?>

    @Query(
        """
        SELECT * FROM policy_versions
        WHERE round_id = :roundId AND policy_version = :policyVersion
        LIMIT 1
        """
    )
    fun observePolicyVersion(roundId: String, policyVersion: Long): Flow<PolicyVersionEntity?>

    @Query("SELECT COUNT(*) FROM audit_logs")
    fun observeAuditCount(): Flow<Int>

    @Query("SELECT * FROM backup_metadata ORDER BY created_at_epoch_ms DESC")
    fun observeBackupHistory(): Flow<List<BackupMetadataEntity>>

    @Query("SELECT * FROM backup_metadata ORDER BY created_at_epoch_ms DESC")
    suspend fun getBackupHistory(): List<BackupMetadataEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBackupMetadata(metadata: BackupMetadataEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRound(round: RoundEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPolicyVersion(policyVersion: PolicyVersionEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAuditLog(auditLog: AuditLogEntity): Long

    /**
     * กฎข้อ 01 & Phase 01:
     * การเปิดใช้งานครั้งแรกในโหมดเครื่องบิน (Airplane-Mode First Launch)
     * ต้องทำงานแบบ Atomic ภายใน Room Transaction เดียวกันโดยไม่ต้องต่ออินเทอร์เน็ต
     * และหากมีข้อมูลอยู่แล้วจะไม่เขียนทับหรือรีเซ็ตข้อมูลเดิมโดยเด็ดขาด
     */
    @Transaction
    suspend fun bootstrapDemoSeedIfEmpty(nowEpochMs: Long): Boolean {
        val existingCount = countRounds()
        if (existingCount > 0) {
            return false
        }

        val initialRoundId = "round-demo-initial-001"
        val initialPolicyVersion = 1L

        val demoRound = RoundEntity(
            roundId = initialRoundId,
            roundCode = "DEMO-2026-001",
            titleTh = "งวดสาธิตเริ่มต้น (โหมดออฟไลน์เครื่องเดียว)",
            status = RoundStatus.OPEN.name,
            activePolicyVersion = initialPolicyVersion,
            currencyCode = "THB",
            minorUnitScale = 100,
            isDemoSeed = true,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs
        )

        val demoPolicy = PolicyVersionEntity(
            roundId = initialRoundId,
            policyVersion = initialPolicyVersion,
            defaultTopRateExact = "60",
            defaultBottomRateExact = "60",
            // Demo default cap: 1,000.00 THB = 100,000 minor units (สตางค์) ตาม 07_TEST_FIXTURES
            defaultReceiveCapMinor = 100_000L,
            // Demo payout cap: 6,000,000 minor units (60,000.00 THB)
            defaultPayoutCapMinor = 6_000_000L,
            roundingMode = "HALF_UP",
            isDemoDefault = true,
            changeReason = "ค่าเริ่มต้นสำหรับสาธิต (Demo Defaults D01-D05) เมื่อเปิดแอปครั้งแรกในโหมดออฟไลน์",
            createdAtEpochMs = nowEpochMs
        )

        val initialAudit = AuditLogEntity(
            eventType = "FIRST_LAUNCH_OFFLINE_BOOTSTRAP",
            actorRole = "SYSTEM_LOCAL",
            roundId = initialRoundId,
            summaryTh = "สร้างงวดสาธิต DEMO-2026-001 และนโยบายรุ่นที่ 1 (THB minor=100, rate=60, HALF_UP) ในเครื่องสำเร็จ",
            createdAtEpochMs = nowEpochMs
        )

        insertRound(demoRound)
        insertPolicyVersion(demoPolicy)
        insertAuditLog(initialAudit)
        return true
    }
}
