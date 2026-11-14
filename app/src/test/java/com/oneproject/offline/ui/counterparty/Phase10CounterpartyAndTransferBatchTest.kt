package com.oneproject.offline.ui.counterparty

import com.oneproject.offline.data.local.dao.AuthoritativeCommitDao
import com.oneproject.offline.data.local.dao.BootstrapDao
import com.oneproject.offline.data.local.dao.CartAndQuoteDao
import com.oneproject.offline.data.local.dao.LedgerAndAccountingDao
import com.oneproject.offline.data.local.dao.ManualTransferBatchCreateResult
import com.oneproject.offline.data.local.dao.ManualTransferResponseResult
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
import com.oneproject.offline.data.repository.LedgerAndReconciliationRepository
import com.oneproject.offline.data.repository.OfflineBootstrapRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit Tests สำหรับ Phase 10:
 * - การสร้างใบตีออกด้วยมือในสถานะ PENDING (acceptedAmountMinor = 0, ลดความเสี่ยง = 0)
 * - การบันทึกผลตอบรับ ACCEPTED, PARTIAL, และ REJECTED
 * - การแยกบัญชีตั้งหนี้คู่สัญญา (COUNTERPARTY_SETTLEMENT) ออกจากบัญชีรับจ่ายบิลลูกค้าและความเสี่ยง
 * - การบล็อกการบันทึกผลตอบรับซ้ำในใบตีออกที่ตัดสินแล้ว และการตรวจเพดานสัญญา (maxTransferCapMinor)
 * - การยืนยันว่าระบบปิดการโอนเงิน/ส่งออกภายนอกอัตโนมัติ (isExternalExecutionDisabled = true)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Phase10CounterpartyAndTransferBatchTest {

    private class FakePhase10Backend :
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
        override suspend fun insertBackupMetadata(metadata: BackupMetadataEntity) { backupHistory.add(metadata) }
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
        override fun observeCartForRound(roundId: String): Flow<CartEntity?> = MutableStateFlow(null)
        override suspend fun getCartLines(cartId: String): List<CartLineEntity> = emptyList()
        override fun observeCartLines(cartId: String): Flow<List<CartLineEntity>> = MutableStateFlow(emptyList())
        override suspend fun getMaxLineSequence(cartId: String): Int = 0
        override suspend fun upsertCart(cart: CartEntity) {}
        override suspend fun insertCartLines(lines: List<CartLineEntity>) {}
        override suspend fun updateCartLineAmount(cartId: String, cartLineId: Long, newAmountMinor: Long) {}
        override suspend fun deleteCartLine(cartId: String, cartLineId: Long) {}
        override suspend fun clearCartLines(cartId: String) {}
        override suspend fun insertQuoteSnapshot(quote: QuoteSnapshotEntity) {}
        override suspend fun getQuoteSnapshotById(quoteId: String): QuoteSnapshotEntity? = null

        override suspend fun getLocalActorById(actorId: String): LocalActorEntity? = actors[actorId]
        override suspend fun upsertLocalActor(actor: LocalActorEntity) { actors[actor.actorId] = actor }
        override suspend fun insertApprovalRecord(approval: ApprovalRecordEntity) { approvals.add(approval) }
        override suspend fun getApprovalsForRound(roundId: String): List<ApprovalRecordEntity> = emptyList()
        override suspend fun getDurableOperation(operationKey: String): DurableOperationEntity? = operations[operationKey]
        override suspend fun insertDurableOperation(operation: DurableOperationEntity) { operations[operation.operationKey] = operation }
        override suspend fun getRoundById(roundId: String): RoundEntity? = rounds[roundId]
        override suspend fun getAllRounds(): List<RoundEntity> = rounds.values.toList()
        override suspend fun updateRound(round: RoundEntity) { rounds[round.roundId] = round }
        override suspend fun getPolicyVersion(roundId: String, policyVersion: Long): PolicyVersionEntity? =
            policies.find { it.roundId == roundId && it.policyVersion == policyVersion }
        override suspend fun getPolicyHistoryForRound(roundId: String): List<PolicyVersionEntity> =
            policies.filter { it.roundId == roundId }
        override suspend fun getNumberPolicyOverride(roundId: String, policyVersion: Long, numberTwoDigit: String, side: String): NumberPolicyOverrideEntity? = null
        override suspend fun getOverridesForPolicyVersion(roundId: String, policyVersion: Long): List<NumberPolicyOverrideEntity> = emptyList()
        override suspend fun upsertNumberPolicyOverride(overrideEntity: NumberPolicyOverrideEntity) {}
        override suspend fun getQuoteSnapshot(quoteId: String): QuoteSnapshotEntity? = null
        override suspend fun getNumberSideCounter(roundId: String, numberTwoDigit: String, side: String): NumberSideCounterEntity? = null
        override suspend fun getAllCountersForRound(roundId: String): List<NumberSideCounterEntity> = emptyList()
        override suspend fun upsertNumberSideCounter(counter: NumberSideCounterEntity) {}
        override suspend fun getBillById(billId: String): BillEntity? = bills[billId]
        override suspend fun getBillByHumanCode(humanBillCode: String): BillEntity? = null
        override suspend fun searchBillsInRound(roundId: String, searchQuery: String): List<BillEntity> =
            bills.values.filter { it.roundId == roundId }
        override suspend fun countBillsInRound(roundId: String): Int = bills.size
        override suspend fun insertBill(bill: BillEntity) { bills[bill.billId] = bill }
        override suspend fun updateBill(bill: BillEntity) { bills[bill.billId] = bill }
        override suspend fun insertBillRevision(revision: BillRevisionEntity) { revisions[revision.revisionId] = revision }
        override suspend fun getBillRevisionById(revisionId: String): BillRevisionEntity? = revisions[revisionId]
        override suspend fun getRevisionsForBill(billId: String): List<BillRevisionEntity> = emptyList()
        override suspend fun insertBillLines(lines: List<BillNumberSideLineEntity>) { billLines.addAll(lines) }
        override suspend fun getLinesForRevision(revisionId: String): List<BillNumberSideLineEntity> =
            billLines.filter { it.revisionId == revisionId }
        override suspend fun clearCartLinesById(cartId: String) {}
        override suspend fun getResultRevisionsForRound(roundId: String): List<ResultRevisionEntity> = emptyList()
        override suspend fun getActiveApprovedResultRevision(roundId: String): ResultRevisionEntity? = null
        override suspend fun getResultRevisionById(resultRevisionId: String): ResultRevisionEntity? = null
        override suspend fun insertResultRevision(resultRevision: ResultRevisionEntity) {}
        override suspend fun updateResultRevision(resultRevision: ResultRevisionEntity) {}
        override suspend fun insertWinningEntitlements(entitlements: List<WinningEntitlementEntity>) {}
        override suspend fun getWinningEntitlementsForRevision(resultRevisionId: String): List<WinningEntitlementEntity> = emptyList()
        override suspend fun getWinningEntitlementsForBillInRevision(resultRevisionId: String, billId: String): List<WinningEntitlementEntity> = emptyList()
        override suspend fun getPaymentAllocationsForTargets(targetType: String, targetIds: List<String>): List<PaymentAllocationEntity> = emptyList()

        override suspend fun upsertLedgerAccount(account: LedgerAccountEntity) { ledgerAccounts[account.accountId] = account }
        override suspend fun getAllLedgerAccounts(): List<LedgerAccountEntity> = ledgerAccounts.values.sortedBy { it.accountCode }
        override suspend fun getLedgerAccount(accountId: String): LedgerAccountEntity? = ledgerAccounts[accountId]
        override suspend fun getLedgerTransaction(transactionId: String): LedgerTransactionEntity? = ledgerTransactions[transactionId]
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
        override suspend fun insertPaymentRecord(payment: PaymentRecordEntity) { paymentRecords[payment.paymentId] = payment }
        override suspend fun updatePaymentRecord(payment: PaymentRecordEntity) { paymentRecords[payment.paymentId] = payment }
        override suspend fun getPaymentRecordById(paymentId: String): PaymentRecordEntity? = paymentRecords[paymentId]
        override suspend fun getPaymentRecordsForRound(roundId: String): List<PaymentRecordEntity> =
            paymentRecords.values.filter { it.roundId == roundId }
        override suspend fun insertPaymentAllocation(allocation: PaymentAllocationEntity) { paymentAllocations.add(allocation) }
        override suspend fun getAllocationsForPayment(paymentId: String): List<PaymentAllocationEntity> = emptyList()
        override suspend fun getAllocationsForTarget(targetType: String, targetId: String): List<PaymentAllocationEntity> = emptyList()
        override suspend fun getBillByIdForAllocation(billId: String): BillEntity? = bills[billId]
        override suspend fun getWinningEntitlementByIdForAllocation(entitlementId: String): WinningEntitlementEntity? = null

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
            if (idx >= 0) transferLines[idx] = line
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

    private lateinit var backend: FakePhase10Backend
    private lateinit var bootstrapRepository: OfflineBootstrapRepository
    private lateinit var ledgerRepository: LedgerAndReconciliationRepository
    private val roundId = "round-phase10-test"
    private var currentClockMs = 1_770_000_000_000L

    @Before
    fun setUp() = runTest {
        backend = FakePhase10Backend()
        backend.insertRound(
            RoundEntity(
                roundId = roundId,
                roundCode = "DEMO-2026-010",
                titleTh = "งวดทดสอบ Phase 10",
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
        ledgerRepository = LedgerAndReconciliationRepository(backend, backend)
    }

    @Test
    fun transferBatches_pendingReducesZero_partialAndRejectedBehaveDeterministically_andSeparateSettlement() = runTest {
        var idSeq = 1
        val controller = CounterpartyTransferWorkflowController(
            bootstrapRepository = bootstrapRepository,
            ledgerRepository = ledgerRepository,
            clockEpochMsProvider = { currentClockMs += 1_000L; currentClockMs },
            idGenerator = { prefix -> "$prefix-00${idSeq++}" }
        )
        controller.initializeAndRefresh()

        val initialSnap = controller.uiState.value.roundSnapshot
        assertNotNull(initialSnap)
        assertTrue(initialSnap!!.isExternalExecutionDisabled)

        // 1. สร้างใบตีออกที่ 1: เลข 31 บน ยอด 100.00 บาท (10,000 สตางค์)
        controller.updateDraftLineInputs(
            numberInput = "31",
            side = BetSide.TOP,
            amountMajorInput = "100.00"
        )
        controller.addDraftLineToStage()
        val create1 = controller.submitManualTransferBatchPending()
        assertTrue(create1 is ManualTransferBatchCreateResult.CreatedPending)
        val batch1Id = (create1 as ManualTransferBatchCreateResult.CreatedPending).batchId

        // ตรวจสอบว่าสถานะ PENDING มี acceptedAmount = 0 และ pendingExposureReductionMinor = 0
        val afterCreate1Snap = controller.uiState.value.roundSnapshot!!
        assertEquals(10_000L, afterCreate1Snap.totalPendingSubmittedMinor)
        assertEquals(0L, afterCreate1Snap.pendingExposureReductionMinor)
        assertEquals(0L, afterCreate1Snap.totalAcceptedPrincipalMinor)
        assertEquals(0L, afterCreate1Snap.totalAcceptedRecoverableCeilingMinor)

        // 2. บันทึกผลตอบรับแบบ PARTIAL (รับเพียง 40.00 บาท = 4,000 สตางค์ @ อัตรา 60 -> สิทธิรับคืนตามสัญญา = 240,000 สตางค์)
        controller.updateResponseDraft(
            batchId = batch1Id,
            responseStatus = TransferResponseStatus.PARTIAL,
            partialAcceptedMajorInput = "40.00",
            referenceNoteThInput = "คู่สัญญารับบางส่วน 40 บาทจาก 100 บาท"
        )
        val resp1 = controller.recordManualTransferResponse()
        assertTrue(resp1 is ManualTransferResponseResult.Recorded)
        resp1 as ManualTransferResponseResult.Recorded
        assertEquals(TransferResponseStatus.PARTIAL, resp1.finalBatchStatus)
        assertEquals(4_000L, resp1.totalAcceptedMinor)
        assertEquals(240_000L, resp1.totalAcceptedRecoverablePayoutMinor)
        assertNotNull(resp1.settlementLedgerTransactionId)

        // ตรวจสอบว่าสมุดบัญชีถูกโพสต์แยกในขอบเขต COUNTERPARTY_SETTLEMENT เท่านั้น
        val cpJournals = controller.uiState.value.roundSnapshot!!.counterpartySettlementJournals
        assertEquals(1, cpJournals.size)
        assertEquals(AccountScope.COUNTERPARTY_SETTLEMENT.name, cpJournals[0].accountScope)
        assertEquals(4_000L, cpJournals[0].totalBalancedMinor)

        // 3. พยายามบันทึกผลตอบรับซ้ำในใบตีออกเดิม (batch1Id) -> ต้องถูกปฏิเสธ
        val duplicateResp = ledgerRepository.recordManualTransferResponse(
            responseId = "TRESP-DUP",
            batchId = batch1Id,
            responseStatus = TransferResponseStatus.ACCEPTED,
            manualReferenceNoteTh = "พยายามเปลี่ยนผลซ้ำ",
            settlementLedgerTxId = "LTX-CP-DUP",
            settlementOperationKey = "OP-CP-DUP",
            actorId = "LOCAL_ADMIN",
            nowEpochMs = currentClockMs + 5_000L
        )
        assertTrue(duplicateResp is ManualTransferResponseResult.Rejected)

        // 4. สร้างใบตีออกที่ 2 แล้วบันทึกผลตอบรับเป็น REJECTED -> ต้องได้ยอดรับ = 0 และไม่สร้างรายการบัญชีตั้งหนี้คู่สัญญาเพิ่ม
        controller.updateDraftLineInputs(
            numberInput = "27",
            side = BetSide.BOTTOM,
            amountMajorInput = "80.00"
        )
        val create2 = controller.submitManualTransferBatchPending() as ManualTransferBatchCreateResult.CreatedPending
        controller.updateResponseDraft(
            batchId = create2.batchId,
            responseStatus = TransferResponseStatus.REJECTED,
            referenceNoteThInput = "คู่สัญญาเต็มเพดาน ปฏิเสธรับตีออก"
        )
        val resp2 = controller.recordManualTransferResponse() as ManualTransferResponseResult.Recorded
        assertEquals(TransferResponseStatus.REJECTED, resp2.finalBatchStatus)
        assertEquals(0L, resp2.totalAcceptedMinor)
        assertEquals(0L, resp2.totalAcceptedRecoverablePayoutMinor)
        assertNull(resp2.settlementLedgerTransactionId)
        assertEquals(1, controller.uiState.value.roundSnapshot!!.counterpartySettlementJournals.size)
    }
}
