package com.oneproject.offline.data.repository

import com.oneproject.offline.data.local.dao.BootstrapDao
import com.oneproject.offline.data.local.entity.AuditLogEntity
import com.oneproject.offline.data.local.entity.BackupMetadataEntity
import com.oneproject.offline.data.local.entity.PolicyVersionEntity
import com.oneproject.offline.data.local.entity.RoundEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

data class LocalHeaderState(
    val activeRound: RoundEntity?,
    val activePolicy: PolicyVersionEntity?,
    val auditEventCount: Int
)

class OfflineBootstrapRepository(
    private val bootstrapDao: BootstrapDao
) {
    fun observeLatestRound(): Flow<RoundEntity?> = bootstrapDao.observeLatestRound()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeActivePolicy(): Flow<PolicyVersionEntity?> {
        return bootstrapDao.observeLatestRound().flatMapLatest { round ->
            if (round == null) {
                flowOf(null)
            } else {
                bootstrapDao.observePolicyVersion(
                    roundId = round.roundId,
                    policyVersion = round.activePolicyVersion
                )
            }
        }
    }

    fun observeAuditCount(): Flow<Int> = bootstrapDao.observeAuditCount()

    fun observeBackupHistory(): Flow<List<BackupMetadataEntity>> = bootstrapDao.observeBackupHistory()

    suspend fun getBackupHistory(): List<BackupMetadataEntity> = bootstrapDao.getBackupHistory()

    suspend fun recordBackupEvent(
        metadata: BackupMetadataEntity,
        auditSummaryTh: String,
        roundId: String? = null
    ) {
        bootstrapDao.insertBackupMetadata(metadata)
        bootstrapDao.insertAuditLog(
            AuditLogEntity(
                eventType = metadata.actionType,
                actorRole = "LOCAL_ADMIN",
                roundId = roundId,
                summaryTh = auditSummaryTh,
                createdAtEpochMs = metadata.createdAtEpochMs
            )
        )
    }

    suspend fun ensureFirstLaunchSeed(nowEpochMs: Long): Boolean {
        return bootstrapDao.bootstrapDemoSeedIfEmpty(nowEpochMs)
    }
}
