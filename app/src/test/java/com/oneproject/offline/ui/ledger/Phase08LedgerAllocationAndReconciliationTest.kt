package com.oneproject.offline.ui.ledger

import com.oneproject.offline.data.local.dao.AtomicBillCommitRequest
import com.oneproject.offline.data.local.dao.AtomicCommitResult
import com.oneproject.offline.data.local.dao.AuthoritativeCommitDao
import com.oneproject.offline.data.local.dao.BootstrapDao
import com.oneproject.offline.data.local.dao.CartAndQuoteDao
import com.oneproject.offline.data.local.dao.LedgerAndAccountingDao
import com.oneproject.offline.data.local.dao.LedgerEntryDraft
import com.oneproject.offline.data.local.dao.LedgerPostingResult
import com.oneproject.offline.data.local.dao.PaymentAllocationResult
import com.oneproject.offline.data.local.dao.PaymentRecordResult
import com.oneproject.offline.data.local.dao.PersistedLineCommitInput
import com.oneproject.offline.data.local.dao.ResultApprovalOutcome
import com.oneproject.offline.data.local.dao.ResultRevisionApprovalRequest
import com.oneproject.offline.data.local.entity.AccountScope
import com.oneproject.offline.data.local.entity.ApprovalRecordEntity
import com.oneproject.offline.data.local.entity.AuditLogEntity
import com.oneproject.offline.data.local.entity.BackupMetadataEntity
import com.oneproject.offline.data.local.entity.BetSide
import com.oneproject.offline.data.local.entity.BillEntity
import com.oneproject.offline.data.local.entity.BillNumberSideLineEntity
import com.oneproject.offline.data.local.entity.BillRevisionEntity
import com.oneproject.offline.data.local.entity.CartEntity
import com.oneproject.offline.data.local.entity.CartLineEntity
import com.oneproject.offline.data.local.entity.DurableOperationEntity
import com.oneproject.offline.data.local.entity.LedgerAccountEntity
import com.oneproject.offline.data.local.entity.LedgerEntryDirection
import com.oneproject.offline.data.local.entity.LedgerEntryEntity
import com.oneproject.offline.data.local.entity.LedgerTransactionEntity
import com.oneproject.offline.data.local.entity.LocalActorEntity
import com.oneproject.offline.data.local.entity.NumberPolicyOverrideEntity
import com.oneproject.offline.data.local.entity.NumberSideCounterEntity
import com.oneproject.offline.data.local.entity.PaymentAllocationEntity
import com.oneproject.offline.data.local.entity.PaymentRecordEntity
import com.oneproject.offline.data.local.entity.PolicyVersionEntity
import com.oneproject.offline.data.local.entity.QuoteSnapshotEntity
import com.oneproject.offline.data.local.entity.ResultRevisionEntity
import com.oneproject.offline.data.local.entity.RoundEntity
import com.oneproject.offline.data.local.entity.RoundStatus
import com.oneproject.offline.data.local.entity.WinningEntitlementEntity
import com.oneproject.offline.data.repository.AuthoritativeOrderRepository
import com.oneproject.offline.data.repository.LedgerAndReconciliationRepository
import com.oneproject.offline.data.repository.OfflineBootstrapRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit Tests สำหรับ Phase 08:
 * - การสร้างผังบัญชีสาธิต (Demo Chart of Accounts — D09)
 * - การโพสต์รายการบัญชีคู่สมดุล (SUM(DEBIT) == SUM(CREDIT), สกุลเงินและขอบเขตเดียวกัน, Idempotent Replay)
 * - การกลับรายการแบบผูกอ้างอิง (Linked Reversal — ห้ามแก้ทับรายการที่โพสต์แล้ว, ห้ามกลับรายการซ้ำ)
 * - การบันทึกรับ/จ่ายเงินสดและจัดสรรยอดไม่เกินเพดานจริง (Bounded Allocations)
 * - การแยกยอดสั่งซื้อบิล != เงินสดที่เก็บได้ และ สิทธิรางวัล != เงินสดที่จ่ายแล้ว
 * - การกระทบยอดเงินสดในเครื่อง (Book vs Physical Cash Reconciliation & Trial Balance)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Phase08LedgerAllocationAndReconciliationTest {

    private class FakePhase08Backend :
        BootstrapDao,
        CartAndQuoteDao,
        AuthoritativeCommitDao,
        LedgerAndAccountingDao {

        val rounds = linkedMapOf<String, RoundEntity>()
        val policies = mutableListOf<PolicyVersionEntity>()
        val overrides = mutableListOf<NumberPolicyOverrideEntity>()
        val actors = linkedMapOf<String, LocalActorEntity>()
        val approvals = mutableListOf<ApprovalRecordEntity>()
        val operations = linkedMapOf<String, DurableOperationEntity>()
        val carts = linkedMapOf<String, CartEntity>()
        val cartLines = mutableListOf<CartLineEntity>()
        val quotes = linkedMapOf<String, QuoteSnapshotEntity>()
        val counters = mutableListOf<NumberSideCounterEntity>()
        val bills = linkedMapOf<String, BillEntity>()
        val revisions = linkedMapOf<String, BillRevisionEntity>()
        val billLines = mutableListOf<BillNumberSideLineEntity>()
        val resultRevisions = linkedMapOf<String, ResultRevisionEntity>()
        val winningEntitlements = mutableListOf<WinningEntitlementEntity>()
        val ledgerAccounts = linkedMapOf<String, LedgerAccountEntity>()
        val ledgerTransactions = linkedMapOf<String, LedgerTransactionEntity>()
        val ledgerEntries = mutableListOf<LedgerEntryEntity>()
        val paymentRecords = linkedMapOf<String, PaymentRecordEntity>()
        val paymentAllocations = mutableListOf<PaymentAllocationEntity>()
        val backupHistory = mutableListOf<BackupMetadataEntity>()
        val audits = mutableListOf<AuditLogEntity>()

        private var nextCartLineId = 1L
        private var nextBillLineId = 1L
        private var nextEntryId = 1L
        private var nextAuditId = 1L

        private val activeRoundFlow = MutableStateFlow<RoundEntity?>(null)
        private val auditCountFlow = MutableStateFlow(0)

        // BootstrapDao
        override suspend fun countRounds(): Int = rounds.size
        override fun observeLatestRound(): Flow<RoundEntity?> = activeRoundFlow
        override fun observePolicyVersion(roundId: String, policyVersion: Long): Flow<PolicyVersionEntity?> =
            MutableStateFlow(policies.find { it.roundId == roundId && it.policyVersion == policyVersion })
        override fun observeAuditCount(): Flow<Int> = auditCountFlow
        override fun observeBackupHistory(): Flow<List<BackupMetadataEntity>> = MutableStateFlow(backupHistory)
        override suspend fun getBackupHistory(): List<BackupMetadataEntity> = backupHistory
        override suspend fun insertBackupMetadata(metadata: BackupMetadataEntity) {
            backupHistory.add(metadata)
        }
        override suspend fun insertRound(round: RoundEntity) {
            rounds[round.roundId] = round
            activeRoundFlow.value = round
        }
        override suspend fun insertPolicyVersion(policyVersion: PolicyVersionEntity): Long {
            val rowId = (policies.size + 1).toLong()
            policies.add(policyVersion.copy(policyRowId = rowId))
            return rowId
        }
        override suspend fun insertAuditLog(auditLog: AuditLogEntity): Long {
            val id = nextAuditId++
            audits.add(auditLog.copy(auditId = id))
            auditCountFlow.value = audits.size
            return id
        }

        // CartAndQuoteDao
        override suspend fun getCartById(cartId: String): CartEntity? = carts[cartId]
        override fun observeCartForRound(roundId: String): Flow<CartEntity?> =
            MutableStateFlow(carts.values.filter { it.roundId == roundId }.maxByOrNull { it.updatedAtEpochMs })
        override suspend fun getCartLines(cartId: String): List<CartLineEntity> =
            cartLines.filter { it.cartId == cartId }.sortedBy { it.lineSequence }
        override fun observeCartLines(cartId: String): Flow<List<CartLineEntity>> =
            MutableStateFlow(cartLines.filter { it.cartId == cartId }.sortedBy { it.lineSequence })
        override suspend fun getMaxLineSequence(cartId: String): Int =
            cartLines.filter { it.cartId == cartId }.maxOfOrNull { it.lineSequence } ?: 0
        override suspend fun upsertCart(cart: CartEntity) {
            carts[cart.cartId] = cart
        }
        override suspend fun insertCartLines(lines: List<CartLineEntity>) {
            lines.forEach { line ->
                val id = if (line.cartLineId == 0L) nextCartLineId++ else line.cartLineId
                cartLines.add(line.copy(cartLineId = id))
            }
        }
        override suspend fun updateCartLineAmount(cartId: String, cartLineId: Long, newAmountMinor: Long) {
            val idx = cartLines.indexOfFirst { it.cartId == cartId && it.cartLineId == cartLineId }
            if (idx >= 0) {
                cartLines[idx] = cartLines[idx].copy(amountMinor = newAmountMinor)
            }
        }
        override suspend fun deleteCartLine(cartId: String, cartLineId: Long) {
            cartLines.removeAll { it.cartId == cartId && it.cartLineId == cartLineId }
        }
        override suspend fun clearCartLines(cartId: String) {
            cartLines.removeAll { it.cartId == cartId }
        }
        override suspend fun insertQuoteSnapshot(quote: QuoteSnapshotEntity) {
            quotes[quote.quoteId] = quote
        }
        override suspend fun getQuoteSnapshotById(quoteId: String): QuoteSnapshotEntity? = quotes[quoteId]

        // AuthoritativeCommitDao
        override suspend fun getLocalActorById(actorId: String): LocalActorEntity? = actors[actorId]
        override suspend fun upsertLocalActor(actor: LocalActorEntity) {
            actors[actor.actorId] = actor
        }
        override suspend fun insertApprovalRecord(approval: ApprovalRecordEntity) {
            approvals.add(approval)
        }
        override suspend fun getApprovalsForRound(roundId: String): List<ApprovalRecordEntity> =
            approvals.filter { it.roundId == roundId }
        override suspend fun getDurableOperation(operationKey: String): DurableOperationEntity? =
            operations[operationKey]
        override suspend fun insertDurableOperation(operation: DurableOperationEntity) {
            operations[operation.operationKey] = operation
        }
        override suspend fun getRoundById(roundId: String): RoundEntity? = rounds[roundId]
        override suspend fun getAllRounds(): List<RoundEntity> = rounds.values.toList()
        override suspend fun updateRound(round: RoundEntity) {
            rounds[round.roundId] = round
            activeRoundFlow.value = round
        }
        override suspend fun getPolicyVersion(roundId: String, policyVersion: Long): PolicyVersionEntity? =
            policies.find { it.roundId == roundId && it.policyVersion == policyVersion }
        override suspend fun getPolicyHistoryForRound(roundId: String): List<PolicyVersionEntity> =
            policies.filter { it.roundId == roundId }.sortedByDescending { it.policyVersion }
        override suspend fun getNumberPolicyOverride(
            roundId: String,
            policyVersion: Long,
            numberTwoDigit: String,
            side: String
        ): NumberPolicyOverrideEntity? = overrides.find {
            it.roundId == roundId && it.policyVersion == policyVersion &&
                it.numberTwoDigit == numberTwoDigit && it.side == side
        }
        override suspend fun getOverridesForPolicyVersion(
            roundId: String,
            policyVersion: Long
        ): List<NumberPolicyOverrideEntity> = overrides.filter {
            it.roundId == roundId && it.policyVersion == policyVersion
        }
        override suspend fun upsertNumberPolicyOverride(overrideEntity: NumberPolicyOverrideEntity) {
            overrides.removeAll {
                it.roundId == overrideEntity.roundId &&
                    it.policyVersion == overrideEntity.policyVersion &&
                    it.numberTwoDigit == overrideEntity.numberTwoDigit &&
                    it.side == overrideEntity.side
            }
            overrides.add(overrideEntity)
        }
        override suspend fun getQuoteSnapshot(quoteId: String): QuoteSnapshotEntity? = quotes[quoteId]
        override suspend fun getNumberSideCounter(
            roundId: String,
            numberTwoDigit: String,
            side: String
        ): NumberSideCounterEntity? = counters.find {
            it.roundId == roundId && it.numberTwoDigit == numberTwoDigit && it.side == side
        }
        override suspend fun getAllCountersForRound(roundId: String): List<NumberSideCounterEntity> =
            counters.filter { it.roundId == roundId }
        override suspend fun upsertNumberSideCounter(counter: NumberSideCounterEntity) {
            counters.removeAll {
                it.roundId == counter.roundId &&
                    it.numberTwoDigit == counter.numberTwoDigit &&
                    it.side == counter.side
            }
            counters.add(counter)
        }
        override suspend fun getBillById(billId: String): BillEntity? = bills[billId]
        override suspend fun getBillByHumanCode(humanBillCode: String): BillEntity? =
            bills.values.find { it.humanBillCode == humanBillCode }
        override suspend fun searchBillsInRound(roundId: String, searchQuery: String): List<BillEntity> =
            bills.values.filter { it.roundId == roundId }
        override suspend fun countBillsInRound(roundId: String): Int =
            bills.values.count { it.roundId == roundId }
        override suspend fun insertBill(bill: BillEntity) {
            bills[bill.billId] = bill
        }
        override suspend fun updateBill(bill: BillEntity) {
            bills[bill.billId] = bill
        }
        override suspend fun insertBillRevision(revision: BillRevisionEntity) {
            revisions[revision.revisionId] = revision
        }
        override suspend fun getBillRevisionById(revisionId: String): BillRevisionEntity? = revisions[revisionId]
        override suspend fun getRevisionsForBill(billId: String): List<BillRevisionEntity> =
            revisions.values.filter { it.billId == billId }.sortedBy { it.revisionNumber }
        override suspend fun insertBillLines(lines: List<BillNumberSideLineEntity>) {
            lines.forEach { line ->
                val id = if (line.billLineId == 0L) nextBillLineId++ else line.billLineId
                billLines.add(line.copy(billLineId = id))
            }
        }
        override suspend fun getLinesForRevision(revisionId: String): List<BillNumberSideLineEntity> =
            billLines.filter { it.revisionId == revisionId }.sortedBy { it.lineNumber }
        override suspend fun clearCartLinesById(cartId: String) {
            cartLines.removeAll { it.cartId == cartId }
        }
        override suspend fun getResultRevisionsForRound(roundId: String): List<ResultRevisionEntity> =
            resultRevisions.values.filter { it.roundId == roundId }.sortedByDescending { it.revisionNumber }
        override suspend fun getActiveApprovedResultRevision(roundId: String): ResultRevisionEntity? =
            resultRevisions.values
                .filter { it.roundId == roundId && it.status == "APPROVED" }
                .maxByOrNull { it.revisionNumber }
        override suspend fun getResultRevisionById(resultRevisionId: String): ResultRevisionEntity? =
            resultRevisions[resultRevisionId]
        override suspend fun insertResultRevision(resultRevision: ResultRevisionEntity) {
            resultRevisions[resultRevision.resultRevisionId] = resultRevision
        }
        override suspend fun updateResultRevision(resultRevision: ResultRevisionEntity) {
            resultRevisions[resultRevision.resultRevisionId] = resultRevision
        }
        override suspend fun insertWinningEntitlements(entitlements: List<WinningEntitlementEntity>) {
            winningEntitlements.addAll(entitlements)
        }
        override suspend fun getWinningEntitlementsForRevision(resultRevisionId: String): List<WinningEntitlementEntity> =
            winningEntitlements.filter { it.resultRevisionId == resultRevisionId }
        override suspend fun getWinningEntitlementsForBillInRevision(
            resultRevisionId: String,
            billId: String
        ): List<WinningEntitlementEntity> =
            winningEntitlements.filter { it.resultRevisionId == resultRevisionId && it.billId == billId }
        override suspend fun getPaymentAllocationsForTargets(
            targetType: String,
            targetIds: List<String>
        ): List<PaymentAllocationEntity> =
            paymentAllocations.filter { it.targetType == targetType && it.targetId in targetIds }

        // LedgerAndAccountingDao
        override suspend fun upsertLedgerAccount(account: LedgerAccountEntity) {
            ledgerAccounts[account.accountId] = account
        }
        override suspend fun getAllLedgerAccounts(): List<LedgerAccountEntity> =
            ledgerAccounts.values.sortedBy { it.accountCode }
        override suspend fun getLedgerAccount(accountId: String): LedgerAccountEntity? =
            ledgerAccounts[accountId]
        override suspend fun getLedgerTransaction(transactionId: String): LedgerTransactionEntity? =
            ledgerTransactions[transactionId]
        override suspend fun getLedgerTransactionByOperationKey(operationKey: String): LedgerTransactionEntity? =
            ledgerTransactions.values.find { it.operationKey == operationKey }
        override suspend fun findReversalForTransaction(originalTransactionId: String): LedgerTransactionEntity? =
            ledgerTransactions.values.find { it.reversesTransactionId == originalTransactionId }
        override suspend fun getTransactionsForRound(roundId: String?): List<LedgerTransactionEntity> =
            ledgerTransactions.values
                .filter { roundId == null || it.roundId == roundId || it.roundId == null }
                .sortedByDescending { it.postedAtEpochMs }
        override suspend fun getEntriesForTransaction(transactionId: String): List<LedgerEntryEntity> =
            ledgerEntries.filter { it.transactionId == transactionId }.sortedBy { it.entrySequence }
        override suspend fun insertLedgerTransaction(transaction: LedgerTransactionEntity) {
            ledgerTransactions[transaction.transactionId] = transaction
        }
        override suspend fun insertLedgerEntries(entries: List<LedgerEntryEntity>) {
            entries.forEach { entry ->
                val id = if (entry.entryId == 0L) nextEntryId++ else entry.entryId
                ledgerEntries.add(entry.copy(entryId = id))
            }
        }
        override suspend fun insertPaymentRecord(payment: PaymentRecordEntity) {
            paymentRecords[payment.paymentId] = payment
        }
        override suspend fun updatePaymentRecord(payment: PaymentRecordEntity) {
            paymentRecords[payment.paymentId] = payment
        }
        override suspend fun getPaymentRecordById(paymentId: String): PaymentRecordEntity? =
            paymentRecords[paymentId]
        override suspend fun getPaymentRecordsForRound(roundId: String): List<PaymentRecordEntity> =
            paymentRecords.values.filter { it.roundId == roundId }.sortedByDescending { it.recordedAtEpochMs }
        override suspend fun insertPaymentAllocation(allocation: PaymentAllocationEntity) {
            paymentAllocations.add(allocation)
        }
        override suspend fun getAllocationsForPayment(paymentId: String): List<PaymentAllocationEntity> =
            paymentAllocations.filter { it.paymentId == paymentId }
        override suspend fun getAllocationsForTarget(
            targetType: String,
            targetId: String
        ): List<PaymentAllocationEntity> =
            paymentAllocations.filter { it.targetType == targetType && it.targetId == targetId }
        override suspend fun getBillByIdForAllocation(billId: String): BillEntity? =
            bills[billId]
        override suspend fun getWinningEntitlementByIdForAllocation(entitlementId: String): WinningEntitlementEntity? =
            winningEntitlements.find { it.entitlementId == entitlementId }
        override suspend fun upsertCounterparty(counterparty: CounterpartyEntity) {}
        override suspend fun getAllCounterparties(): List<CounterpartyEntity> = emptyList()
        override suspend fun getCounterpartyById(counterpartyId: String): CounterpartyEntity? = null
        override suspend fun upsertCounterpartyContract(contract: CounterpartyContractEntity) {}
        override suspend fun getContractsForRound(roundId: String): List<CounterpartyContractEntity> = emptyList()
        override suspend fun getContractById(contractId: String): CounterpartyContractEntity? = null
        override suspend fun insertManualTransferBatch(batch: ManualTransferBatchEntity) {}
        override suspend fun updateManualTransferBatch(batch: ManualTransferBatchEntity) {}
        override suspend fun getTransferBatchById(batchId: String): ManualTransferBatchEntity? = null
        override suspend fun getTransferBatchesForRound(roundId: String): List<ManualTransferBatchEntity> = emptyList()
        override suspend fun insertManualTransferLines(lines: List<ManualTransferLineEntity>) {}
        override suspend fun updateManualTransferLine(line: ManualTransferLineEntity) {}
        override suspend fun getTransferLinesForBatch(batchId: String): List<ManualTransferLineEntity> = emptyList()
        override suspend fun getTransferLinesForRound(roundId: String): List<ManualTransferLineEntity> = emptyList()
        override suspend fun insertTransferResponse(response: TransferResponseEntity) {}
        override suspend fun getResponsesForBatch(batchId: String): List<TransferResponseEntity> = emptyList()
        override suspend fun getResponsesForRound(roundId: String): List<TransferResponseEntity> = emptyList()
    }

    private lateinit var backend: FakePhase08Backend
    private lateinit var bootstrapRepository: OfflineBootstrapRepository
    private lateinit var orderRepository: AuthoritativeOrderRepository
    private lateinit var ledgerRepository: LedgerAndReconciliationRepository

    private val roundId = "round-phase08-test"
    private var currentClockMs = 1_750_000_000_000L

    @Before
    fun setUp() = runTest {
        backend = FakePhase08Backend()
        backend.upsertLocalActor(
            LocalActorEntity(
                actorId = "LOCAL_ADMIN",
                actorCode = "ADMIN-01",
                displayNameTh = "ผู้ดูแลเครื่อง",
                primaryRole = "LOCAL_ADMIN",
                isActive = true,
                createdAtEpochMs = currentClockMs
            )
        )
        backend.insertRound(
            RoundEntity(
                roundId = roundId,
                roundCode = "DEMO-2026-008",
                titleTh = "งวดทดสอบ Phase 08",
                status = RoundStatus.OPEN.name,
                activePolicyVersion = 1L,
                currencyCode = "THB",
                minorUnitScale = 100,
                isDemoSeed = true,
                createdAtEpochMs = currentClockMs,
                updatedAtEpochMs = currentClockMs
            )
        )
        backend.insertPolicyVersion(
            PolicyVersionEntity(
                roundId = roundId,
                policyVersion = 1L,
                defaultTopRateExact = "60",
                defaultBottomRateExact = "60",
                defaultReceiveCapMinor = 100_000L,
                defaultPayoutCapMinor = 6_000_000L,
                roundingMode = "HALF_UP",
                isDemoDefault = true,
                changeReason = "Demo v1",
                createdAtEpochMs = currentClockMs
            )
        )

        bootstrapRepository = OfflineBootstrapRepository(backend)
        orderRepository = AuthoritativeOrderRepository(backend, backend)
        ledgerRepository = LedgerAndReconciliationRepository(backend, backend)

        // สร้างบิลที่ 1 ยอดรวม 350.00 บาท (35,000 สตางค์):
        // 31 TOP 100.00 (payout 6,000.00), 31 BOTTOM 50.00 (payout 3,000.00), 27 BOTTOM 200.00 (payout 12,000.00)
        val cart01Lines = listOf(
            PersistedLineCommitInput("31", BetSide.TOP.name, 10_000L, "60", 600_000L),
            PersistedLineCommitInput("31", BetSide.BOTTOM.name, 5_000L, "60", 300_000L),
            PersistedLineCommitInput("27", BetSide.BOTTOM.name, 20_000L, "60", 1_200_000L)
        )
        backend.insertQuoteSnapshot(
            QuoteSnapshotEntity(
                quoteId = "q-cart01",
                roundId = roundId,
                policyVersion = 1L,
                payloadSha256 = "hash-cart01",
                totalAmountMinor = 35_000L,
                totalProvisionalPayoutMinor = 2_100_000L,
                lineCount = 3,
                distinctNumberCount = 2,
                createdAtEpochMs = currentClockMs,
                expiresAtEpochMs = currentClockMs + 300_000L
            )
        )
        val commit1 = orderRepository.commitNewBillAtomically(
            AtomicBillCommitRequest(
                operationKey = "op-commit-cart01",
                payloadSha256 = "hash-cart01",
                quoteId = "q-cart01",
                cartIdToClear = null,
                roundId = roundId,
                expectedPolicyVersion = 1L,
                newBillId = "bill-cart01",
                newRevisionId = "rev-cart01-1",
                explicitHumanBillCode = "BILL-0001",
                customerNoteTh = "บิลทดสอบ CART01",
                actorId = "LOCAL_ADMIN",
                lines = cart01Lines,
                nowEpochMs = currentClockMs + 1_000L
            )
        )
        assertTrue(commit1 is AtomicCommitResult.Committed)
    }

    @Test
    fun balancedDoubleEntryPosting_enforcesBalanceSameScopeAndIdempotency() = runTest {
        val accounts = ledgerRepository.ensureDemoChartOfAccountsSeeded(currentClockMs)
        assertEquals(9, accounts.size)
        assertTrue(accounts.all { it.isDemoChart })

        // 1. ทดสอบโพสต์รายการที่ไม่สมดุล (Debit 10,000 != Credit 9,500) -> ต้องถูกปฏิเสธ
        val unbalancedResult = backend.postBalancedTransaction(
            transactionId = "LTX-UNBALANCED",
            operationKey = "OP-UNBALANCED",
            roundId = roundId,
            currencyCode = "THB",
            accountScope = AccountScope.ORDER_SETTLEMENT.name,
            reversesTransactionId = null,
            memoTh = "รายการไม่สมดุล",
            postedByActorId = "LOCAL_ADMIN",
            entries = listOf(
                LedgerEntryDraft(LedgerAndAccountingDao.DEMO_ACC_ORDER_CASH, LedgerEntryDirection.DEBIT, 10_000L),
                LedgerEntryDraft(LedgerAndAccountingDao.DEMO_ACC_BILL_RECEIVABLE, LedgerEntryDirection.CREDIT, 9_500L)
            ),
            nowEpochMs = currentClockMs + 2_000L
        )
        assertTrue(unbalancedResult is LedgerPostingResult.Rejected)

        // 2. ทดสอบโพสต์รายการที่ข้ามขอบเขตบัญชี (ORDER_SETTLEMENT ผสมกับ OPERATING_CASH) -> ต้องถูกปฏิเสธ
        val crossScopeResult = backend.postBalancedTransaction(
            transactionId = "LTX-CROSS-SCOPE",
            operationKey = "OP-CROSS-SCOPE",
            roundId = roundId,
            currencyCode = "THB",
            accountScope = AccountScope.ORDER_SETTLEMENT.name,
            reversesTransactionId = null,
            memoTh = "ข้ามขอบเขตบัญชี",
            postedByActorId = "LOCAL_ADMIN",
            entries = listOf(
                LedgerEntryDraft(LedgerAndAccountingDao.DEMO_ACC_ORDER_CASH, LedgerEntryDirection.DEBIT, 10_000L),
                LedgerEntryDraft(LedgerAndAccountingDao.DEMO_ACC_OPERATING_DRAWER_CASH, LedgerEntryDirection.CREDIT, 10_000L)
            ),
            nowEpochMs = currentClockMs + 3_000L
        )
        assertTrue(crossScopeResult is LedgerPostingResult.Rejected)

        // 3. โพสต์รายการบัญชีคู่สมดุลถูกต้อง (Debit 35,000 == Credit 35,000 ในขอบเขต ORDER_SETTLEMENT)
        val validPost = ledgerRepository.postManualBalancedTransaction(
            transactionId = "LTX-VALID-01",
            operationKey = "OP-VALID-01",
            roundId = roundId,
            currencyCode = "THB",
            accountScope = AccountScope.ORDER_SETTLEMENT.name,
            memoTh = "ตั้งลูกหนี้ค่าซื้อบิล BILL-0001",
            postedByActorId = "LOCAL_ADMIN",
            entries = listOf(
                LedgerEntryDraft(LedgerAndAccountingDao.DEMO_ACC_BILL_RECEIVABLE, LedgerEntryDirection.DEBIT, 35_000L),
                LedgerEntryDraft(LedgerAndAccountingDao.DEMO_ACC_ORDER_REVENUE, LedgerEntryDirection.CREDIT, 35_000L)
            ),
            nowEpochMs = currentClockMs + 4_000L
        )
        assertTrue(validPost is LedgerPostingResult.Posted)
        validPost as LedgerPostingResult.Posted
        assertFalse(validPost.replayed)
        assertEquals(35_000L, validPost.totalBalancedMinor)

        // 4. ส่งซ้ำด้วย operationKey เดิม -> ต้องคืนค่า replayed = true และไม่สร้างรายการซ้ำ
        val replayedPost = ledgerRepository.postManualBalancedTransaction(
            transactionId = "LTX-VALID-01-DUP",
            operationKey = "OP-VALID-01",
            roundId = roundId,
            currencyCode = "THB",
            accountScope = AccountScope.ORDER_SETTLEMENT.name,
            memoTh = "ตั้งลูกหนี้ค่าซื้อบิล BILL-0001 ซ้ำ",
            postedByActorId = "LOCAL_ADMIN",
            entries = listOf(
                LedgerEntryDraft(LedgerAndAccountingDao.DEMO_ACC_BILL_RECEIVABLE, LedgerEntryDirection.DEBIT, 35_000L),
                LedgerEntryDraft(LedgerAndAccountingDao.DEMO_ACC_ORDER_REVENUE, LedgerEntryDirection.CREDIT, 35_000L)
            ),
            nowEpochMs = currentClockMs + 5_000L
        )
        assertTrue(replayedPost is LedgerPostingResult.Posted)
        replayedPost as LedgerPostingResult.Posted
        assertTrue(replayedPost.replayed)
        assertEquals("LTX-VALID-01", replayedPost.transactionId)
        assertEquals(1, backend.ledgerTransactions.size)
    }

    @Test
    fun linkedReversal_createsMirrorEntries_preservesOriginal_andBlocksDoubleReversal() = runTest {
        var idSeq = 1
        val controller = LedgerAndReconciliationWorkflowController(
            bootstrapRepository = bootstrapRepository,
            ledgerRepository = ledgerRepository,
            clockEpochMsProvider = { currentClockMs += 1_000L; currentClockMs },
            idGenerator = { prefix -> "$prefix-00${idSeq++}" }
        )
        controller.initializeAndRefresh()

        // โพสต์รายการต้นทาง 250.00 บาท (25,000 สตางค์)
        controller.updateManualEntryDraft(
            scopeName = AccountScope.OPERATING_CASH.name,
            debitAccountId = LedgerAndAccountingDao.DEMO_ACC_OPERATING_DRAWER_CASH,
            creditAccountId = LedgerAndAccountingDao.DEMO_ACC_OPERATING_RESERVE_EQUITY,
            amountMajorInput = "250.00",
            memoThInput = "เติมเงินสดสำรองต้นงวด"
        )
        val origPost = controller.postManualBalancedJournal(customOperationKey = "OP-ORIG-250")
        assertTrue(origPost is LedgerPostingResult.Posted)
        val origTxId = (origPost as LedgerPostingResult.Posted).transactionId

        // สร้าง Linked Reversal อ้างอิงรายการต้นทาง
        controller.selectTransactionForReversal(
            transactionId = origTxId,
            reversalReasonTh = "บันทึกยอดเงินสดสำรองผิดจำนวน"
        )
        val revPost = controller.postLinkedReversal(customOperationKey = "OP-REV-250")
        assertTrue(revPost is LedgerPostingResult.Posted)
        val revTxId = (revPost as LedgerPostingResult.Posted).transactionId

        // ตรวจสอบว่ารายการต้นทางไม่ถูกแก้ไขทับ และรายการกลับบัญชีสลับฝั่ง DEBIT <-> CREDIT ครบถ้วน
        val origEntries = backend.getEntriesForTransaction(origTxId)
        val revEntries = backend.getEntriesForTransaction(revTxId)
        assertEquals(2, origEntries.size)
        assertEquals(2, revEntries.size)
        assertEquals(LedgerEntryDirection.DEBIT.name, origEntries[0].direction)
        assertEquals(LedgerEntryDirection.CREDIT.name, revEntries[0].direction)
        assertEquals(origEntries[0].accountId, revEntries[0].accountId)
        assertEquals(origEntries[0].amountMinor, revEntries[0].amountMinor)

        // งบทดลองหลังกลับรายการต้องมียอดสุทธิ Net(Dr - Cr) == 0 ทุกบัญชี
        val recon = controller.uiState.value.reconciliationSnapshot
        assertNotNull(recon)
        recon!!
        assertTrue(recon.isTrialBalanceBalanced)
        assertEquals(50_000L, recon.totalLedgerDebitMinor)
        assertEquals(50_000L, recon.totalLedgerCreditMinor)
        assertTrue(recon.accountTrialBalances.all { it.netDebitMinusCreditMinor == 0L })

        // ห้ามกลับรายการซ้ำในรายการต้นทางเดิม และห้ามกลับรายการของรายการที่เป็น Reversal
        val secondReversalAttempt = ledgerRepository.postLinkedReversalTransaction(
            newReversalTransactionId = "REV-DUP",
            operationKey = "OP-REV-DUP",
            originalTransactionId = origTxId,
            reversalReasonTh = "พยายามกลับรายการซ้ำ",
            postedByActorId = "LOCAL_ADMIN",
            nowEpochMs = currentClockMs + 10_000L
        )
        assertTrue(secondReversalAttempt is LedgerPostingResult.Rejected)

        val reverseTheReversalAttempt = ledgerRepository.postLinkedReversalTransaction(
            newReversalTransactionId = "REV-OF-REV",
            operationKey = "OP-REV-OF-REV",
            originalTransactionId = revTxId,
            reversalReasonTh = "พยายามกลับรายการของ Reversal",
            postedByActorId = "LOCAL_ADMIN",
            nowEpochMs = currentClockMs + 11_000L
        )
        assertTrue(reverseTheReversalAttempt is LedgerPostingResult.Rejected)
    }

    @Test
    fun boundedAllocations_andCashReconciliation_separateOrderFromCashAndEntitlementFromPaid() = runTest {
        // อนุมัติผลรางวัล บน=31, ล่าง=27 เพื่อให้เกิดสิทธิได้รับรางวัล 18,000.00 บาท (1,800,000 สตางค์)
        val approvalRes = orderRepository.approveResultRevisionAtomically(
            ResultRevisionApprovalRequest(
                operationKey = "op-approve-phase08-res",
                payloadSha256 = "hash-approve-phase08",
                roundId = roundId,
                resultRevisionId = "res-rev-001",
                winningTopTwoDigit = "31",
                winningBottomTwoDigit = "27",
                sourceNoteTh = "ประกาศผลทดสอบ Phase 08",
                approvalReasonTh = "ยืนยันผลรางวัลเพื่อทดสอบจ่ายเงินรางวัล",
                approvedByActorId = "LOCAL_ADMIN",
                nowEpochMs = currentClockMs + 5_000L
            )
        )
        assertTrue(approvalRes is ResultApprovalOutcome.Approved)

        var idSeq = 1
        val controller = LedgerAndReconciliationWorkflowController(
            bootstrapRepository = bootstrapRepository,
            ledgerRepository = ledgerRepository,
            clockEpochMsProvider = { currentClockMs += 1_000L; currentClockMs },
            idGenerator = { prefix -> "$prefix-00${idSeq++}" }
        )
        controller.initializeAndRefresh()

        // 1. ก่อนรับ/จ่ายเงินสด:
        // ยอดบิล = 35,000 สตางค์ (เก็บเงินแล้ว 0 สตางค์)
        // ยอดสิทธิรางวัล = 1,800,000 สตางค์ (จ่ายเงินแล้ว 0 สตางค์)
        val initialRecon = controller.uiState.value.reconciliationSnapshot!!
        assertEquals(35_000L, initialRecon.totalActiveOrderAmountMinor)
        assertEquals(0L, initialRecon.totalAllocatedToBillsMinor)
        assertEquals(35_000L, initialRecon.totalUncollectedBillReceivableMinor)
        assertEquals(1_800_000L, initialRecon.totalWinningEntitlementMinor)
        assertEquals(0L, initialRecon.totalAllocatedToEntitlementsMinor)
        assertEquals(1_800_000L, initialRecon.totalUnpaidEntitlementPayableMinor)

        // 2. บันทึกรับเงินสดจากลูกค้า 200.00 บาท (20,000 สตางค์)
        controller.updatePaymentDraft(
            paymentDirection = LedgerAndAccountingDao.PAYMENT_DIRECTION_INFLOW_COLLECTION,
            amountMajorInput = "200.00",
            evidenceNoteThInput = "รับเงินสดบางส่วนจากลูกค้าบิล BILL-0001"
        )
        val inflowRecord = controller.recordPaymentWithJournal(customOperationKey = "OP-INFLOW-200")
        assertTrue(inflowRecord is PaymentRecordResult.Recorded)
        inflowRecord as PaymentRecordResult.Recorded

        // 3. พยายามจัดสรร 250.00 บาท (เกินยอด Payment 200.00 บาท) -> ต้องถูกปฏิเสธ
        controller.updateAllocationDraft(
            paymentId = inflowRecord.paymentId,
            targetType = LedgerAndAccountingDao.TARGET_TYPE_BILL_RECEIVABLE,
            targetId = "bill-cart01",
            allocationAmountMajorInput = "250.00"
        )
        val overPaymentAlloc = controller.allocatePaymentBounded()
        assertTrue(overPaymentAlloc is PaymentAllocationResult.Rejected)

        // 4. จัดสรร 200.00 บาท เข้าบิล bill-cart01 -> สำเร็จ (เหลือลูกหนี้ค่าบิลค้างเก็บ 150.00 บาท = 15,000 สตางค์)
        controller.fillMaxRemainingAllocationForSelection()
        assertEquals("200.00", controller.uiState.value.allocationAmountMajorInput)
        val validBillAlloc = controller.allocatePaymentBounded()
        assertTrue(validBillAlloc is PaymentAllocationResult.Allocated)

        // 5. บันทึกรับเงินสดเพิ่มอีก 300.00 บาท แล้วพยายามจัดสรร 200.00 บาท เข้าบิลเดิมที่เหลือค้างเพียง 150.00 บาท -> ต้องถูกปฏิเสธ
        controller.updatePaymentDraft(
            paymentDirection = LedgerAndAccountingDao.PAYMENT_DIRECTION_INFLOW_COLLECTION,
            amountMajorInput = "300.00",
            evidenceNoteThInput = "รับเงินสดก้อนที่สอง"
        )
        val inflowRecord2 = controller.recordPaymentWithJournal(customOperationKey = "OP-INFLOW-300") as PaymentRecordResult.Recorded
        controller.updateAllocationDraft(
            paymentId = inflowRecord2.paymentId,
            targetType = LedgerAndAccountingDao.TARGET_TYPE_BILL_RECEIVABLE,
            targetId = "bill-cart01",
            allocationAmountMajorInput = "200.00"
        )
        val overBillCapAlloc = controller.allocatePaymentBounded()
        assertTrue(overBillCapAlloc is PaymentAllocationResult.Rejected)

        // 6. บันทึกจ่ายเงินสดรางวัล 5,000.00 บาท (500,000 สตางค์) และจัดสรรเข้าสิทธิรางวัลบรรทัดแรก (ที่มีเพดาน 6,000.00 บาท)
        controller.updatePaymentDraft(
            paymentDirection = LedgerAndAccountingDao.PAYMENT_DIRECTION_OUTFLOW_ENTITLEMENT,
            amountMajorInput = "5000.00",
            evidenceNoteThInput = "จ่ายเงินสดรางวัลบางส่วนงวดแรก"
        )
        val outflowRecord = controller.recordPaymentWithJournal(customOperationKey = "OP-OUTFLOW-5000") as PaymentRecordResult.Recorded
        val firstEntitlementId = controller.uiState.value.winningEntitlementTargets.first().entitlementId
        controller.updateAllocationDraft(
            paymentId = outflowRecord.paymentId,
            targetType = LedgerAndAccountingDao.TARGET_TYPE_WINNING_ENTITLEMENT,
            targetId = firstEntitlementId,
            allocationAmountMajorInput = "5000.00"
        )
        val validEntitlementAlloc = controller.allocatePaymentBounded()
        assertTrue(validEntitlementAlloc is PaymentAllocationResult.Allocated)

        // 7. ตรวจสอบผลกระทบยอดเงินสด (Opening = 10,000.00 บาท, Inflows = 500.00 บาท, Outflows = 5,000.00 บาท => Expected Closing = 5,500.00 บาท)
        controller.updateCashReconciliationInputs(
            openingCashMajorInput = "10000.00",
            countedPhysicalCashMajorInput = "5450.00", // นับจริงได้ 5,450.00 บาท (เงินสดขาดบัญชี -50.00 บาท = -5,000 สตางค์)
            minimumRequiredCashMajorInput = "6000.00" // ขั้นต่ำ 6,000.00 บาท -> เกิด Shortfall 500.00 บาท (50,000 สตางค์)
        )
        controller.recalculateCashReconciliation()

        val finalRecon = controller.uiState.value.reconciliationSnapshot!!
        assertEquals(35_000L, finalRecon.totalActiveOrderAmountMinor)
        assertEquals(20_000L, finalRecon.totalAllocatedToBillsMinor)
        assertEquals(15_000L, finalRecon.totalUncollectedBillReceivableMinor)
        assertEquals(30_000L, finalRecon.totalUnallocatedInflowMinor)

        assertEquals(1_800_000L, finalRecon.totalWinningEntitlementMinor)
        assertEquals(500_000L, finalRecon.totalAllocatedToEntitlementsMinor)
        assertEquals(1_300_000L, finalRecon.totalUnpaidEntitlementPayableMinor)
        assertEquals(0L, finalRecon.totalUnallocatedOutflowMinor)

        assertEquals(1_000_000L, finalRecon.openingOperatingCashMinor)
        assertEquals(50_000L, finalRecon.totalRecordedCashInflowMinor)
        assertEquals(500_000L, finalRecon.totalRecordedCashOutflowMinor)
        assertEquals(550_000L, finalRecon.expectedBookClosingCashMinor)
        assertEquals(545_000L, finalRecon.countedPhysicalCashMinor)
        assertEquals(-5_000L, finalRecon.physicalCashVarianceMinor)
        assertEquals(50_000L, finalRecon.shortfallMinor)
        assertTrue(finalRecon.hasShortfallAlert)
        assertTrue(finalRecon.isTrialBalanceBalanced)
    }
}
