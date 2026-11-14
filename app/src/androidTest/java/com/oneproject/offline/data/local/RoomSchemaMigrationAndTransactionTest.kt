package com.oneproject.offline.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oneproject.offline.data.local.dao.AtomicBillCommitRequest
import com.oneproject.offline.data.local.dao.AtomicCommitResult
import com.oneproject.offline.data.local.dao.LedgerEntryDraft
import com.oneproject.offline.data.local.dao.LedgerPostingResult
import com.oneproject.offline.data.local.dao.PersistedLineCommitInput
import com.oneproject.offline.data.local.entity.AccountScope
import com.oneproject.offline.data.local.entity.LedgerAccountEntity
import com.oneproject.offline.data.local.entity.LedgerEntryDirection
import com.oneproject.offline.data.local.entity.QuoteSnapshotEntity
import com.oneproject.offline.data.local.migration.OneProjectMigrations
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation Test บน Room SQLite จริงสำหรับตรวจสอบ Schema v2,
 * Atomic Bill Commit, Balanced Double-Entry Ledger และการคงอยู่ของข้อมูล
 */
@RunWith(AndroidJUnit4::class)
class RoomSchemaMigrationAndTransactionTest {

    private lateinit var database: OneProjectDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(
            context,
            OneProjectDatabase::class.java
        )
            .addMigrations(OneProjectMigrations.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun fullSchemaV2_commitsBillAndBalancedLedgerTransactionAtomically() = runBlocking {
        val now = 1_762_761_600_000L
        val bootstrapDao = database.bootstrapDao()
        val commitDao = database.authoritativeCommitDao()
        val cartDao = database.cartAndQuoteDao()
        val ledgerDao = database.ledgerAndAccountingDao()

        assertTrue(bootstrapDao.bootstrapDemoSeedIfEmpty(now))

        val roundId = "round-demo-initial-001"
        cartDao.insertQuoteSnapshot(
            QuoteSnapshotEntity(
                quoteId = "q-room-01",
                cartId = "c-room-01",
                roundId = roundId,
                policyVersion = 1L,
                payloadSha256 = "hash-room-01",
                totalAmountMinor = 35_000L,
                lineCount = 3,
                distinctNumberCount = 2,
                createdAtEpochMs = now,
                expiresAtEpochMs = now + 60_000L
            )
        )

        val commitResult = commitDao.executeAtomicBillCommit(
            AtomicBillCommitRequest(
                operationKey = "op-room-01",
                payloadSha256 = "hash-room-01",
                quoteId = "q-room-01",
                cartIdToClear = null,
                roundId = roundId,
                expectedPolicyVersion = 1L,
                newBillId = "bill-room-01",
                newRevisionId = "rev-room-01",
                customerNoteTh = "Room Test",
                actorId = "SYSTEM_LOCAL",
                lines = listOf(
                    PersistedLineCommitInput("31", "TOP", 10_000L, "60", 600_000L),
                    PersistedLineCommitInput("31", "BOTTOM", 5_000L, "60", 300_000L),
                    PersistedLineCommitInput("27", "BOTTOM", 20_000L, "60", 1_200_000L)
                ),
                nowEpochMs = now + 500L
            )
        )
        assertTrue(commitResult is AtomicCommitResult.Committed)

        // ทดสอบการโพสต์บัญชีคู่ (Balanced Ledger Entry)
        ledgerDao.upsertLedgerAccount(
            LedgerAccountEntity(
                accountId = "acc-cash",
                accountCode = "1010-DEMO",
                accountNameTh = "เงินสดในมือ (สาธิต)",
                accountScope = AccountScope.ORDER_SETTLEMENT.name,
                currencyCode = "THB",
                isDemoChart = true,
                createdAtEpochMs = now
            )
        )
        ledgerDao.upsertLedgerAccount(
            LedgerAccountEntity(
                accountId = "acc-ar",
                accountCode = "1110-DEMO",
                accountNameTh = "ลูกหนี้รายการรับ (สาธิต)",
                accountScope = AccountScope.ORDER_SETTLEMENT.name,
                currencyCode = "THB",
                isDemoChart = true,
                createdAtEpochMs = now
            )
        )

        val ledgerResult = ledgerDao.postBalancedTransaction(
            transactionId = "tx-room-01",
            operationKey = "op-ledger-01",
            roundId = roundId,
            currencyCode = "THB",
            accountScope = AccountScope.ORDER_SETTLEMENT.name,
            reversesTransactionId = null,
            memoTh = "รับชำระเงินสดบิล DEMO-2026-001-B00001",
            postedByActorId = "SYSTEM_LOCAL",
            entries = listOf(
                LedgerEntryDraft("acc-cash", LedgerEntryDirection.DEBIT, 35_000L),
                LedgerEntryDraft("acc-ar", LedgerEntryDirection.CREDIT, 35_000L)
            ),
            nowEpochMs = now + 1_000L
        )
        assertTrue(ledgerResult is LedgerPostingResult.Posted)
        assertEquals(35_000L, (ledgerResult as LedgerPostingResult.Posted).totalBalancedMinor)
    }
}
