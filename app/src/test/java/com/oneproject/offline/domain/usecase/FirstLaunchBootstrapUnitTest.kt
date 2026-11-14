package com.oneproject.offline.domain.usecase

import com.oneproject.offline.data.local.dao.BootstrapDao
import com.oneproject.offline.data.local.entity.AuditLogEntity
import com.oneproject.offline.data.local.entity.BackupMetadataEntity
import com.oneproject.offline.data.local.entity.PolicyVersionEntity
import com.oneproject.offline.data.local.entity.RoundEntity
import com.oneproject.offline.data.repository.OfflineBootstrapRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit Test สำหรับตรวจสอบการทำงานของ Airplane-Mode First Launch Bootstrap (Phase 01)
 */
class FirstLaunchBootstrapUnitTest {

    private class FakeInMemoryBootstrapDao : BootstrapDao {
        private val rounds = MutableStateFlow<List<RoundEntity>>(emptyList())
        private val policies = MutableStateFlow<List<PolicyVersionEntity>>(emptyList())
        private val audits = MutableStateFlow<List<AuditLogEntity>>(emptyList())
        private val backups = MutableStateFlow<List<BackupMetadataEntity>>(emptyList())

        override suspend fun countRounds(): Int = rounds.value.size

        override fun observeLatestRound(): Flow<RoundEntity?> =
            rounds.map { list -> list.maxByOrNull { it.createdAtEpochMs } }

        override fun observePolicyVersion(
            roundId: String,
            policyVersion: Long
        ): Flow<PolicyVersionEntity?> =
            policies.map { list ->
                list.firstOrNull { it.roundId == roundId && it.policyVersion == policyVersion }
            }

        override fun observeAuditCount(): Flow<Int> = audits.map { it.size }

        override fun observeBackupHistory(): Flow<List<BackupMetadataEntity>> = backups

        override suspend fun getBackupHistory(): List<BackupMetadataEntity> = backups.value

        override suspend fun insertBackupMetadata(metadata: BackupMetadataEntity) {
            backups.value = listOf(metadata) + backups.value
        }

        override suspend fun insertRound(round: RoundEntity) {
            rounds.value = rounds.value + round
        }

        override suspend fun insertPolicyVersion(policyVersion: PolicyVersionEntity): Long {
            val nextId = (policies.value.size + 1).toLong()
            policies.value = policies.value + policyVersion.copy(policyRowId = nextId)
            return nextId
        }

        override suspend fun insertAuditLog(auditLog: AuditLogEntity): Long {
            val nextId = (audits.value.size + 1).toLong()
            audits.value = audits.value + auditLog.copy(auditId = nextId)
            return nextId
        }
    }

    @Test
    fun firstLaunch_whenDatabaseEmpty_seedsDemoRoundPolicyAndAuditAtomically_andSecondLaunchPreservesData() = runTest {
        val fakeDao = FakeInMemoryBootstrapDao()
        val repository = OfflineBootstrapRepository(fakeDao)
        val fixedTimestamp = 1_762_761_600_000L
        val useCase = EnsureFirstLaunchBootstrapUseCase(
            repository = repository,
            clockEpochMsProvider = { fixedTimestamp }
        )

        // เปิดแอปครั้งแรกในโหมดเครื่องบิน (ฐานข้อมูลว่าง)
        val firstLaunch = useCase()
        assertTrue(firstLaunch.seededInitialDemoData)
        assertEquals(fixedTimestamp, firstLaunch.checkedAtEpochMs)

        val round = repository.observeLatestRound().first()
        val policy = repository.observeActivePolicy().first()
        val auditCount = repository.observeAuditCount().first()

        assertNotNull(round)
        assertEquals("DEMO-2026-001", round?.roundCode)
        assertEquals("THB", round?.currencyCode)
        assertEquals(100, round?.minorUnitScale)
        assertTrue(round?.isDemoSeed == true)

        assertNotNull(policy)
        assertEquals(1L, policy?.policyVersion)
        assertEquals("60", policy?.defaultTopRateExact)
        assertEquals("60", policy?.defaultBottomRateExact)
        assertEquals(100_000L, policy?.defaultReceiveCapMinor)
        assertEquals("HALF_UP", policy?.roundingMode)
        assertTrue(policy?.isDemoDefault == true)

        assertEquals(1, auditCount)

        // เปิดแอปซ้ำครั้งที่สอง: ต้องไม่เขียนทับหรือสร้างงวดซ้ำ
        val secondLaunch = useCase()
        assertFalse(secondLaunch.seededInitialDemoData)
        assertEquals(1, fakeDao.countRounds())
        assertEquals(1, repository.observeAuditCount().first())
    }
}
