package com.oneproject.offline.ui.risk

import com.oneproject.offline.data.local.dao.AtomicBillCommitRequest
import com.oneproject.offline.data.local.dao.AuthoritativeCommitDao
import com.oneproject.offline.data.local.dao.BootstrapDao
import com.oneproject.offline.data.local.dao.CartAndQuoteDao
import com.oneproject.offline.data.local.dao.LedgerAndAccountingDao
import com.oneproject.offline.data.local.dao.ManualTransferBatchCreateResult
import com.oneproject.offline.data.local.dao.ManualTransferLineDraft
import com.oneproject.offline.data.local.dao.ManualTransferResponseResult
import com.oneproject.offline.data.local.dao.PersistedLineCommitInput
import com.oneproject.offline.data.local.entity.ApprovalRecordEntity
import com.oneproject.offline.data.local.entity.AuditLogEntity
import com.oneproject.offline.data.local.entity.BackupMetadataEntity
import com.oneproject.offline.data.local.entity.BetSide
import com.oneproject.offline.data.local.entity.BillEntity
import com.oneproject.offline.data.local.entity.BillNumberSideLineEntity
import com.oneproject.offline.data.local.entity.BillRevisionEntity
import com.oneproject.offline.data.local.entity.CartEntity
import com.oneproject.offline.data.local.entity.CartLineEntity
import com.oneproject.offline.data.local.entity.CounterpartyContractEntity
import com.oneproject.offline.data.local.entity.CounterpartyEntity
import com.oneproject.offline.data.local.entity.DurableOperationEntity
import com.oneproject.offline.data.local.entity.LedgerAccountEntity
import com.oneproject.offline.data.local.entity.LedgerEntryEntity
import com.oneproject.offline.data.local.entity.LedgerTransactionEntity
import com.oneproject.offline.data.local.entity.LocalActorEntity
import com.oneproject.offline.data.local.entity.ManualTransferBatchEntity
import com.oneproject.offline.data.local.entity.ManualTransferLineEntity
import com.oneproject.offline.data.local.entity.NumberPolicyOverrideEntity
import com.oneproject.offline.data.local.entity.NumberSideCounterEntity
import com.oneproject.offline.data.local.entity.PaymentAllocationEntity
import com.oneproject.offline.data.local.entity.PaymentRecordEntity
import com.oneproject.offline.data.local.entity.PolicyVersionEntity
import com.oneproject.offline.data.local.entity.QuoteSnapshotEntity
import com.oneproject.offline.data.local.entity.ResultRevisionEntity
import com.oneproject.offline.data.local.entity.RoundEntity
import com.oneproject.offline.data.local.entity.RoundStatus
import com.oneproject.offline.data.local.entity.TransferResponseEntity
import com.oneproject.offline.data.local.entity.TransferResponseStatus
import com.oneproject.offline.data.local.entity.WinningEntitlementEntity
import com.oneproject.offline.data.repository.AuthoritativeOrderRepository
import com.oneproject.offline.data.repository.LedgerAndReconciliationRepository
import com.oneproject.offline.data.repository.OfflineBootstrapRepository
import com.oneproject.offline.domain.engine.RiskActiveBillLineInput
import com.oneproject.offline.domain.engine.RiskOutcomeAndCashScenarioEngine
import com.oneproject.offline.domain.engine.RiskTransferLineInput
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
 * Unit Tests สำหรับ Phase 09:
 * - การคำนวณ gross(outcome) และ net(outcome) บนปริภูมิผลลัพธ์ 100 × 100 (10,000 valid outcomes — D06)
 * - การห้ามนำค่าสูงสุดคนละเลขมาลบกันข้ามผลลัพธ์ (Do not subtract unrelated maxima)
 * - การยืนยันว่า PENDING และ REJECTED ลดความเสี่ยงได้ 0 สตางค์
 * - การจำลองสถานการณ์เงินสด (CASH01 fixture + ไม่นับซ้ำลูกหนี้ค่าบิลค้างเก็บ + ไม่นับสิทธิรับคืนเป็นเงินสดรับประกันในสถานการณ์มาตรฐาน)
 * - การตรวจจับข้อมูลล้าสมัย (Stale Risk Detection) และการบล็อกการตัดสินใจเมื่อข้อมูลเปลี่ยนหลังเวลา as-of
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Phase09RiskDashboardAndOutcomeEngineTest {

    private class FakePhase09Backend :
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
        val counterparties = linkedMapOf<String, CounterpartyEntity>()
        val contracts = linkedMapOf<String, CounterpartyContractEntity>()
        val transferBatches = linkedMapOf<String, ManualTransferBatchEntity>()
        val transferLines = mutableListOf<ManualTransferLineEntity>()
        val transferResponses = mutableListOf<TransferResponseEntity>()
        val backupHistory = mutableListOf<BackupMetadataEntity>()
        val audits = mutableListOf<AuditLogEntity>()

        private var nextCartLineId = 1L
        private var nextBillLineId = 1L
        private var nextEntryId = 1L
        private var nextTransferLineId = 1L
        private var nextAuditId = 1L

        private val activeRoundFlow = MutableStateFlow<RoundEntity?>(null)
        private val auditCountFlow = MutableStateFlow(0)

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

        override suspend fun getCartById(cartId: String): CartEntity? = carts[cartId]
        override fun observeCartForRound(roundId: String): Flow<CartEntity?> =
            MutableStateFlow(carts.values.filter { it.roundId == roundId }.maxByOrNull { it.updatedAtEpochMs })
        override suspend fun getCartLines(cartId: String): List<CartLineEntity> =
            cartLines.filter { it.cartId == cartId }.sortedBy { it.lineSequence }
        override fun observeCartLines(cartId: String): Flow<List<CartLineEntity>> =
            MutableStateFlow(cartLines.filter { it.cartId == cartId }.sortedBy { it.lineSequence })
        override suspend fun getMaxLineSequence(cartId: String): Int =
            cartLines.filter { it.cartId == cartId }.maxOfOrNull { it.lineSequence } ?: 0
        override suspend fun upsertCart(cart: CartEntity) { carts[cart.cartId] = cart }
        override suspend fun insertCartLines(lines: List<CartLineEntity>) {
            lines.forEach { line ->
                val id = if (line.cartLineId == 0L) nextCartLineId++ else line.cartLineId
                cartLines.add(line.copy(cartLineId = id))
            }
        }
        override suspend fun updateCartLineAmount(cartId: String, cartLineId: Long, newAmountMinor: Long) {
            val idx = cartLines.indexOfFirst { it.cartId == cartId && it.cartLineId == cartLineId }
            if (idx >= 0) cartLines[idx] = cartLines[idx].copy(amountMinor = newAmountMinor)
        }
        override suspend fun deleteCartLine(cartId: String, cartLineId: Long) {
            cartLines.removeAll { it.cartId == cartId && it.cartLineId == cartLineId }
        }
        override suspend fun clearCartLines(cartId: String) {
            cartLines.removeAll { it.cartId == cartId }
        }
        override suspend fun insertQuoteSnapshot(quote: QuoteSnapshotEntity) { quotes[quote.quoteId] = quote }
        override suspend fun getQuoteSnapshotById(quoteId: String): QuoteSnapshotEntity? = quotes[quoteId]

        override suspend fun getLocalActorById(actorId: String): LocalActorEntity? = actors[actorId]
        override suspend fun upsertLocalActor(actor: LocalActorEntity) { actors[actor.actorId] = actor }
        override suspend fun insertApprovalRecord(approval: ApprovalRecordEntity) { approvals.add(approval) }
        override suspend fun getApprovalsForRound(roundId: String): List<ApprovalRecordEntity> =
            approvals.filter { it.roundId == roundId }
        override suspend fun getDurableOperation(operationKey: String): DurableOperationEntity? = operations[operationKey]
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
        override suspend fun insertBill(bill: BillEntity) { bills[bill.billId] = bill }
        override suspend fun updateBill(bill: BillEntity) { bills[bill.billId] = bill }
        override suspend fun insertBillRevision(revision: BillRevisionEntity) { revisions[revision.revisionId] = revision }
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
        override suspend fun clearCartLinesById(cartId: String) { cartLines.removeAll { it.cartId == cartId } }
        override suspend fun getResultRevisionsForRound(roundId: String): List<ResultRevisionEntity> =
            resultRevisions.values.filter { it.roundId == roundId }.sortedByDescending { it.revisionNumber }
        override suspend fun getActiveApprovedResultRevision(roundId: String): ResultRevisionEntity? =
            resultRevisions.values.filter { it.roundId == roundId && it.status == "APPROVED" }.maxByOrNull { it.revisionNumber }
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

        override suspend fun upsertLedgerAccount(account: LedgerAccountEntity) {
            ledgerAccounts[account.accountId] = account
        }
        override suspend fun getAllLedgerAccounts(): List<LedgerAccountEntity> =
            ledgerAccounts.values.sortedBy { it.accountCode }
        override suspend fun getLedgerAccount(accountId: String): LedgerAccountEntity? = ledgerAccounts[accountId]
        override suspend fun getLedgerTransaction(transactionId: String): LedgerTransactionEntity? =
            ledgerTransactions[transactionId]
        override suspend fun getLedgerTransactionByOperationKey(operationKey: String): LedgerTransactionEntity? =
            ledgerTransactions.values.find { it.operationKey == operationKey }
        override suspend fun findReversalForTransaction(originalTransactionId: String): LedgerTransactionEntity? =
            ledgerTransactions.values.find { it.reversesTransactionId == originalTransactionId }
        override suspend fun getTransactionsForRound(roundId: String?): List<LedgerTransactionEntity> =
            ledgerTransactions.values.filter { roundId == null || it.roundId == roundId || it.roundId == null }
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
        override suspend fun getPaymentRecordById(paymentId: String): PaymentRecordEntity? = paymentRecords[paymentId]
        override suspend fun getPaymentRecordsForRound(roundId: String): List<PaymentRecordEntity> =
            paymentRecords.values.filter { it.roundId == roundId }
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
        override suspend fun getBillByIdForAllocation(billId: String): BillEntity? = bills[billId]
        override suspend fun getWinningEntitlementByIdForAllocation(entitlementId: String): WinningEntitlementEntity? =
            winningEntitlements.find { it.entitlementId == entitlementId }

        override suspend fun upsertCounterparty(counterparty: CounterpartyEntity) {
            counterparties[counterparty.counterpartyId] = counterparty
        }
        override suspend fun getAllCounterparties(): List<CounterpartyEntity> =
            counterparties.values.sortedBy { it.counterpartyCode }
        override suspend fun getCounterpartyById(counterpartyId: String): CounterpartyEntity? =
            counterparties[counterpartyId]
        override suspend fun upsertCounterpartyContract(contract: CounterpartyContractEntity) {
            contracts[contract.contractId] = contract
        }
        override suspend fun getContractsForRound(roundId: String): List<CounterpartyContractEntity> =
            contracts.values.filter { it.roundId == roundId }
        override suspend fun getContractById(contractId: String): CounterpartyContractEntity? =
            contracts[contractId]
        override suspend fun insertManualTransferBatch(batch: ManualTransferBatchEntity) {
            transferBatches[batch.batchId] = batch
        }
        override suspend fun updateManualTransferBatch(batch: ManualTransferBatchEntity) {
            transferBatches[batch.batchId] = batch
        }
        override suspend fun getTransferBatchById(batchId: String): ManualTransferBatchEntity? =
            transferBatches[batchId]
        override suspend fun getTransferBatchesForRound(roundId: String): List<ManualTransferBatchEntity> =
            transferBatches.values.filter { it.roundId == roundId }.sortedByDescending { it.createdAtEpochMs }
        override suspend fun insertManualTransferLines(lines: List<ManualTransferLineEntity>) {
            lines.forEach { line ->
                val id = if (line.transferLineId == 0L) nextTransferLineId++ else line.transferLineId
                transferLines.add(line.copy(transferLineId = id))
            }
        }
        override suspend fun updateManualTransferLine(line: ManualTransferLineEntity) {
            val idx = transferLines.indexOfFirst { it.transferLineId == line.transferLineId }
            if (idx >= 0) {
                transferLines[idx] = line
            }
        }
        override suspend fun getTransferLinesForBatch(batchId: String): List<ManualTransferLineEntity> =
            transferLines.filter { it.batchId == batchId }.sortedBy { it.transferLineId }
        override suspend fun getTransferLinesForRound(roundId: String): List<ManualTransferLineEntity> =
            transferLines.filter { it.roundId == roundId }.sortedBy { it.transferLineId }
        override suspend fun insertTransferResponse(response: TransferResponseEntity) {
            transferResponses.add(response)
        }
        override suspend fun getResponsesForBatch(batchId: String): List<TransferResponseEntity> =
            transferResponses.filter { it.batchId == batchId }.sortedByDescending { it.recordedAtEpochMs }
        override suspend fun getResponsesForRound(roundId: String): List<TransferResponseEntity> {
            val roundBatchIds = transferBatches.values.filter { it.roundId == roundId }.map { it.batchId }.toSet()
            return transferResponses.filter { it.batchId in roundBatchIds }.sortedByDescending { it.recordedAtEpochMs }
        }
    }

    private lateinit var backend: FakePhase09Backend
    private lateinit var bootstrapRepository: OfflineBootstrapRepository
    private lateinit var orderRepository: AuthoritativeOrderRepository
    private lateinit var ledgerRepository: LedgerAndReconciliationRepository

    private val roundId = "round-phase09-test"
    private var currentClockMs = 1_760_000_000_000L

    @Before
    fun setUp() = runTest {
        backend = FakePhase09Backend()
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
                roundCode = "DEMO-2026-009",
                titleTh = "งวดทดสอบความเสี่ยง Phase 09",
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

        // บันทึกบิล CART01: 31 TOP 10000 (payout 600,000), 31 BOTTOM 5000 (payout 300,000), 27 BOTTOM 20000 (payout 1,200,000)
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
        orderRepository.commitNewBillAtomically(
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
    }

    @Test
    fun riskEngine_evaluates100x100OutcomesSeparately_neverSubtractsUnrelatedMaxima_andIgnoresPending() {
        // มีบิลซื้อ:
        // - 31 TOP: gross = 600,000
        // - 99 TOP: gross = 240,000
        // - 27 BOTTOM: gross = 1,200,000
        // และมีใบตีออกที่คู่สัญญายอมรับแล้ว (ACCEPTED) เฉพาะที่เลข 99 TOP (recoverable = 240,000)
        // และมีใบตีออกสถานะ PENDING ที่เลข 31 TOP (submitted = 10,000, recoverable = 0)
        // กฎเหล็ก:
        // - ห้ามนำ recoverable ของ 99 TOP (240,000) ไปหักออกจาก gross สูงสุดของ 31 TOP (600,000)
        // - ดังนั้น netTop("31") ต้องยังคงเท่ากับ 600,000 และ worstNetJointOutcome ต้องเท่ากับ 600,000 + 1,200,000 = 1,800,000
        val activeLines = listOf(
            RiskActiveBillLineInput("31", BetSide.TOP, 10_000L, 600_000L),
            RiskActiveBillLineInput("99", BetSide.TOP, 4_000L, 240_000L),
            RiskActiveBillLineInput("27", BetSide.BOTTOM, 20_000L, 1_200_000L)
        )
        val transferLines = listOf(
            RiskTransferLineInput(
                batchId = "B-ACCEPTED-99",
                batchStatus = TransferResponseStatus.ACCEPTED.name,
                lineStatus = TransferResponseStatus.ACCEPTED.name,
                numberTwoDigit = "99",
                side = BetSide.TOP,
                submittedAmountMinor = 4_000L,
                acceptedAmountMinor = 4_000L,
                acceptedRecoverablePayoutMinor = 240_000L
            ),
            RiskTransferLineInput(
                batchId = "B-PENDING-31",
                batchStatus = TransferResponseStatus.PENDING.name,
                lineStatus = TransferResponseStatus.PENDING.name,
                numberTwoDigit = "31",
                side = BetSide.TOP,
                submittedAmountMinor = 10_000L,
                acceptedAmountMinor = 0L,
                acceptedRecoverablePayoutMinor = 0L
            )
        )

        val snap = RiskOutcomeAndCashScenarioEngine.evaluateValidOutcomesRiskMaxima(
            roundId = roundId,
            policyVersion = 1L,
            defaultTopPayoutCapMinor = 6_000_000L,
            defaultBottomPayoutCapMinor = 6_000_000L,
            payoutCapOverridesByKey = emptyMap(),
            activeBillLines = activeLines,
            transferLines = transferLines,
            computedAsOfEpochMs = 2_000L,
            latestDataMutationEpochMs = 1_500L
        )

        assertEquals(10_000, snap.evaluatedValidOutcomeCount)
        assertEquals(0L, snap.pendingExposureReductionMinor)
        assertEquals(10_000L, snap.totalPendingSubmittedAmountMinor)

        // ตรวจสอบว่า 99 TOP ลดเหลือ Net = 0 แต่ 31 TOP ยังคงมี Net = 600,000 (ไม่ถูกหักข้ามเลข)
        val cell99Top = snap.topCells.first { it.numberTwoDigit == "99" }
        assertEquals(240_000L, cell99Top.grossPayoutMinor)
        assertEquals(240_000L, cell99Top.acceptedRecoverableMinor)
        assertEquals(0L, cell99Top.netPayoutMinor)

        val cell31Top = snap.topCells.first { it.numberTwoDigit == "31" }
        assertEquals(600_000L, cell31Top.grossPayoutMinor)
        assertEquals(0L, cell31Top.acceptedRecoverableMinor)
        assertEquals(600_000L, cell31Top.netPayoutMinor)

        assertEquals("31", snap.maxNetTopCell.numberTwoDigit)
        assertEquals(600_000L, snap.maxNetTopCell.netPayoutMinor)
        assertEquals(1_800_000L, snap.worstNetJointOutcome.jointNetPayoutMinor)
    }

    @Test
    fun dashboardController_computesOutcomeMatchedReduction_verifiesCash01_andBlocksWhenStale() = runTest {
        val controller = RiskDashboardWorkflowController(
            bootstrapRepository = bootstrapRepository,
            ledgerRepository = ledgerRepository,
            clockEpochMsProvider = { currentClockMs += 1_000L; currentClockMs }
        )
        controller.initializeAndRefresh()

        val initialState = controller.uiState.value
        assertFalse(initialState.isSnapshotStale)
        assertFalse(initialState.isDecisionBlocked)

        // ตรวจสอบผล CASH01 จาก 07_TEST_FIXTURES.json
        val cash01 = initialState.cash01IndependentResult
        assertNotNull(cash01)
        cash01!!
        assertEquals(-2_000_000L, cash01.closingCashMinor)
        assertEquals(3_000_000L, cash01.shortfallMinor)
        assertTrue(cash01.hasShortfallAlert)

        // ตรวจสอบว่าลูกหนี้ค่าบิลค้างเก็บ 35,000 สตางค์ ไม่ถูกนับเป็น availableInflowsMinor ในสถานการณ์เงินสด
        val initialCashScenarios = initialState.dashboardBundle!!.cashScenarios
        assertEquals(0L, initialCashScenarios.collectedCashInflowsMinor)
        assertEquals(35_000L, initialCashScenarios.excludedUncollectedReceivableMinor)

        // สร้างใบตีออกที่เลข 27 BOTTOM จำนวน 50.00 บาท (5,000 สตางค์ -> สิทธิรับคืน 300,000 สตางค์) ในสถานะ PENDING
        val (_, contract) = ledgerRepository.ensureDemoCounterpartyAndContractSeeded(roundId, currentClockMs + 5_000L)
        val batchCreate = ledgerRepository.createManualTransferBatchPending(
            batchId = "BATCH-01",
            roundId = roundId,
            contractId = contract.contractId,
            draftLines = listOf(
                ManualTransferLineDraft("27", BetSide.BOTTOM, 5_000L)
            ),
            actorId = "LOCAL_ADMIN",
            nowEpochMs = currentClockMs + 10_000L
        )
        assertTrue(batchCreate is ManualTransferBatchCreateResult.CreatedPending)

        // เนื่องจากมีการสร้าง Batch ใหม่ที่เวลา currentClockMs + 10_000L หลังเวลา as-of ของ Dashboard
        // การตรวจความสดใหม่ต้องระบุว่า Stale และบล็อกการตัดสินใจ!
        val isStaleNow = controller.checkStalenessAgainstDatabase()
        assertTrue(isStaleNow)
        assertTrue(controller.uiState.value.isDecisionBlocked)
        val blockedAttempt = controller.attemptDecisionGuardedByFreshRisk()
        assertTrue(blockedAttempt is StaleGuardedActionOutcome.BlockedByStaleRisk)

        // รีเฟรช Dashboard ตอนที่ Batch ยังเป็น PENDING -> ความเสี่ยงสุทธิสูงสุดต้องยังไม่ลดลง (ยังคงเท่ากับ 1,800,000 สตางค์)
        currentClockMs += 20_000L
        controller.recomputeRiskAndCashSnapshot()
        val afterPendingSnap = controller.uiState.value.dashboardBundle!!.riskSnapshot
        assertFalse(afterPendingSnap.isStale)
        assertEquals(1_800_000L, afterPendingSnap.worstGrossJointOutcome.jointGrossPayoutMinor)
        assertEquals(1_800_000L, afterPendingSnap.worstNetJointOutcome.jointNetPayoutMinor)
        assertEquals(0L, afterPendingSnap.pendingExposureReductionMinor)

        // บันทึกผลตอบรับ ACCEPTED จากคู่สัญญาสำหรับ BATCH-01 ที่เวลาใหม่
        val acceptRes = ledgerRepository.recordManualTransferResponse(
            responseId = "RESP-01",
            batchId = "BATCH-01",
            responseStatus = TransferResponseStatus.ACCEPTED,
            manualReferenceNoteTh = "คู่สัญญายืนยันรับตีออก 27 ล่าง 50 บาท",
            settlementLedgerTxId = "LTX-CP-01",
            settlementOperationKey = "OP-CP-01",
            actorId = "LOCAL_ADMIN",
            nowEpochMs = currentClockMs + 5_000L
        )
        assertTrue(acceptRes is ManualTransferResponseResult.Recorded)

        // รีเฟรช Dashboard หลังคู่สัญญาตอบรับ ACCEPTED -> ความเสี่ยงสุทธิของ 27 ล่าง ลดจาก 1,200,000 เหลือ 900,000
        // ส่งผลให้ worstNetJointOutcome (31 บน + 27 ล่าง) ลดจาก 1,800,000 เหลือ 1,500,000 สตางค์!
        currentClockMs += 10_000L
        controller.recomputeRiskAndCashSnapshot()
        val afterAcceptedSnap = controller.uiState.value.dashboardBundle!!.riskSnapshot
        assertEquals(1_800_000L, afterAcceptedSnap.worstGrossJointOutcome.jointGrossPayoutMinor)
        assertEquals(1_500_000L, afterAcceptedSnap.worstNetJointOutcome.jointNetPayoutMinor)
    }
}
