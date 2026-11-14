package com.oneproject.offline.data.repository

import com.oneproject.offline.core.money.ExactMoneyMath
import com.oneproject.offline.data.local.dao.AuthoritativeCommitDao
import com.oneproject.offline.data.local.dao.LedgerAndAccountingDao
import com.oneproject.offline.data.local.dao.LedgerEntryDraft
import com.oneproject.offline.data.local.dao.LedgerPostingResult
import com.oneproject.offline.data.local.dao.PaymentAllocationResult
import com.oneproject.offline.data.local.dao.PaymentRecordResult
import com.oneproject.offline.data.local.entity.BillStatus
import com.oneproject.offline.data.local.entity.LedgerAccountEntity
import com.oneproject.offline.data.local.entity.LedgerEntryDirection
import com.oneproject.offline.data.local.entity.PaymentAllocationEntity
import com.oneproject.offline.domain.engine.PolicyAndCapEngine

/**
 * Phase 08 Repository สำหรับสมุดบัญชีแยกประเภทคู่ (Manual Double-Entry Ledger),
 * การกลับรายการแบบผูกอ้างอิง (Linked Reversal — ห้ามแก้ทับรายการที่โพสต์แล้ว),
 * การบันทึกรับ/จ่ายเงินสดและจัดสรรยอดแบบมีเพดาน (Bounded Allocations),
 * และการกระทบยอดเงินสดในเครื่อง (Cash & Settlement Reconciliation)
 *
 * หมายเหตุ D09: ใช้ผังบัญชีสาธิต (Demo Chart of Accounts — isDemoChart = true)
 * จนกว่าผู้รับผิดชอบบัญชีจะลงนามอนุมัติผังบัญชีจริง
 */
class LedgerAndReconciliationRepository(
    private val ledgerDao: LedgerAndAccountingDao,
    private val commitDao: AuthoritativeCommitDao
) {

    suspend fun ensureDemoChartOfAccountsSeeded(
        nowEpochMs: Long = System.currentTimeMillis()
    ): List<LedgerAccountEntity> {
        return ledgerDao.ensureDemoChartOfAccountsSeeded(nowEpochMs)
    }

    suspend fun getAllAccounts(
        nowEpochMs: Long = System.currentTimeMillis()
    ): List<LedgerAccountEntity> {
        return ledgerDao.ensureDemoChartOfAccountsSeeded(nowEpochMs)
    }

    suspend fun postManualBalancedTransaction(
        transactionId: String,
        operationKey: String,
        roundId: String?,
        currencyCode: String = "THB",
        accountScope: String,
        memoTh: String,
        postedByActorId: String,
        entries: List<LedgerEntryDraft>,
        nowEpochMs: Long = System.currentTimeMillis()
    ): LedgerPostingResult {
        ledgerDao.ensureDemoChartOfAccountsSeeded(nowEpochMs)
        return ledgerDao.postBalancedTransaction(
            transactionId = transactionId,
            operationKey = operationKey,
            roundId = roundId,
            currencyCode = currencyCode,
            accountScope = accountScope,
            reversesTransactionId = null,
            memoTh = memoTh,
            postedByActorId = postedByActorId,
            entries = entries,
            nowEpochMs = nowEpochMs
        )
    }

    suspend fun postLinkedReversalTransaction(
        newReversalTransactionId: String,
        operationKey: String,
        originalTransactionId: String,
        reversalReasonTh: String,
        postedByActorId: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): LedgerPostingResult {
        ledgerDao.ensureDemoChartOfAccountsSeeded(nowEpochMs)
        return ledgerDao.postLinkedReversalTransaction(
            newReversalTransactionId = newReversalTransactionId,
            operationKey = operationKey,
            originalTransactionId = originalTransactionId,
            reversalReasonTh = reversalReasonTh,
            postedByActorId = postedByActorId,
            nowEpochMs = nowEpochMs
        )
    }

    suspend fun recordPaymentWithLedgerPosting(
        paymentId: String,
        ledgerTransactionId: String,
        operationKey: String,
        roundId: String,
        paymentDirection: String,
        amountMinor: Long,
        evidenceNoteTh: String,
        actorId: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): PaymentRecordResult {
        return ledgerDao.recordPaymentWithLedgerPostingTransaction(
            paymentId = paymentId,
            ledgerTransactionId = ledgerTransactionId,
            operationKey = operationKey,
            roundId = roundId,
            paymentDirection = paymentDirection,
            amountMinor = amountMinor,
            evidenceNoteTh = evidenceNoteTh,
            actorId = actorId,
            nowEpochMs = nowEpochMs
        )
    }

    suspend fun allocatePaymentToTargetBounded(
        allocationId: String,
        paymentId: String,
        targetType: String,
        targetId: String,
        allocationAmountMinor: Long,
        actorId: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): PaymentAllocationResult {
        return ledgerDao.allocatePaymentToTargetTransaction(
            allocationId = allocationId,
            paymentId = paymentId,
            targetType = targetType,
            targetId = targetId,
            allocationAmountMinor = allocationAmountMinor,
            actorId = actorId,
            nowEpochMs = nowEpochMs
        )
    }

    suspend fun getLedgerJournalSnapshotsForRound(
        roundId: String?,
        nowEpochMs: Long = System.currentTimeMillis()
    ): List<LedgerJournalSnapshot> {
        val accountsById = ledgerDao.ensureDemoChartOfAccountsSeeded(nowEpochMs)
            .associateBy { it.accountId }
        val transactions = ledgerDao.getTransactionsForRound(roundId)

        return transactions.map { tx ->
            val entries = ledgerDao.getEntriesForTransaction(tx.transactionId)
            val reversalTx = ledgerDao.findReversalForTransaction(tx.transactionId)
            val lineSnapshots = entries.map { entry ->
                val account = accountsById[entry.accountId]
                LedgerEntryLineSnapshot(
                    entrySequence = entry.entrySequence,
                    accountId = entry.accountId,
                    accountCode = account?.accountCode ?: entry.accountId,
                    accountNameTh = account?.accountNameTh ?: entry.accountId,
                    direction = entry.direction,
                    amountMinor = entry.amountMinor,
                    currencyCode = entry.currencyCode,
                    accountScope = entry.accountScope
                )
            }
            LedgerJournalSnapshot(
                transactionId = tx.transactionId,
                operationKey = tx.operationKey,
                roundId = tx.roundId,
                currencyCode = tx.currencyCode,
                accountScope = tx.accountScope,
                totalBalancedMinor = tx.totalBalancedMinor,
                reversesTransactionId = tx.reversesTransactionId,
                reversedByTransactionId = reversalTx?.transactionId,
                memoTh = tx.memoTh,
                postedByActorId = tx.postedByActorId,
                postedAtEpochMs = tx.postedAtEpochMs,
                lines = lineSnapshots
            )
        }
    }

    suspend fun getPaymentSnapshotsForRound(roundId: String): List<PaymentRecordSnapshot> {
        val payments = ledgerDao.getPaymentRecordsForRound(roundId)
        return payments.map { payment ->
            val allocations = ledgerDao.getAllocationsForPayment(payment.paymentId)
            val unallocatedMinor = ExactMoneyMath.checkedSubtract(
                payment.amountMinor,
                payment.allocatedTotalMinor
            )
            PaymentRecordSnapshot(
                paymentId = payment.paymentId,
                roundId = payment.roundId,
                paymentDirection = payment.paymentDirection,
                amountMinor = payment.amountMinor,
                allocatedTotalMinor = payment.allocatedTotalMinor,
                unallocatedRemainingMinor = unallocatedMinor,
                ledgerTransactionId = payment.ledgerTransactionId,
                evidenceNoteTh = payment.evidenceNoteTh,
                recordedAtEpochMs = payment.recordedAtEpochMs,
                allocations = allocations
            )
        }
    }

    suspend fun getAllocatableTargetsForRound(roundId: String): AllocatableTargetsSnapshot {
        val allBills = commitDao.searchBillsInRound(roundId, "")
        val activeBills = allBills.filter { it.status == BillStatus.ACTIVE.name }

        val billTargets = activeBills.map { bill ->
            val allocations = ledgerDao.getAllocationsForTarget(
                targetType = LedgerAndAccountingDao.TARGET_TYPE_BILL_RECEIVABLE,
                targetId = bill.billId
            )
            val collectedMinor = ExactMoneyMath.checkedSum(allocations.map { it.allocatedAmountMinor })
            val uncollectedMinor = ExactMoneyMath.checkedSubtract(
                bill.currentTotalAmountMinor,
                collectedMinor
            )
            AllocatableBillReceivableTarget(
                billId = bill.billId,
                humanBillCode = bill.humanBillCode,
                customerNoteTh = bill.customerNoteTh,
                orderAmountMinor = bill.currentTotalAmountMinor,
                collectedAllocatedMinor = collectedMinor,
                uncollectedRemainingMinor = uncollectedMinor
            )
        }

        val approvedRevision = commitDao.getActiveApprovedResultRevision(roundId)
        val entitlementTargets = if (approvedRevision != null) {
            val entitlements = commitDao.getWinningEntitlementsForRevision(approvedRevision.resultRevisionId)
            val billsById = allBills.associateBy { it.billId }
            entitlements.map { ent ->
                val allocations = ledgerDao.getAllocationsForTarget(
                    targetType = LedgerAndAccountingDao.TARGET_TYPE_WINNING_ENTITLEMENT,
                    targetId = ent.entitlementId
                )
                val paidMinor = ExactMoneyMath.checkedSum(allocations.map { it.allocatedAmountMinor })
                val unpaidMinor = ExactMoneyMath.checkedSubtract(
                    ent.entitlementPayoutMinor,
                    paidMinor
                )
                val billCode = billsById[ent.billId]?.humanBillCode ?: ent.billId
                AllocatableWinningEntitlementTarget(
                    entitlementId = ent.entitlementId,
                    resultRevisionId = ent.resultRevisionId,
                    billId = ent.billId,
                    humanBillCode = billCode,
                    billLineId = ent.billLineId,
                    entitlementPayoutMinor = ent.entitlementPayoutMinor,
                    paidAllocatedMinor = paidMinor,
                    unpaidRemainingMinor = unpaidMinor
                )
            }
        } else {
            emptyList()
        }

        return AllocatableTargetsSnapshot(
            roundId = roundId,
            activeResultRevisionId = approvedRevision?.resultRevisionId,
            billReceivables = billTargets,
            winningEntitlements = entitlementTargets
        )
    }

    /**
     * คำนวณภาพรวมการกระทบยอดเงินสด ลูกหนี้ค่าบิล เจ้าหนี้สิทธิรางวัล และงบทดลองบัญชีคู่ (Trial Balance)
     * ยืนยันกฎเหล็ก:
     * - orderAmount != collectedCash
     * - winningEntitlement != paidCash
     * - ยอดรวม DEBIT ทั้งหมดในสมุดบัญชี == ยอดรวม CREDIT ทั้งหมดในสมุดบัญชี
     */
    suspend fun getCashReconciliationSnapshot(
        roundId: String,
        openingOperatingCashMinor: Long,
        countedPhysicalCashMinor: Long,
        minimumRequiredCashMinor: Long = 0L,
        nowEpochMs: Long = System.currentTimeMillis()
    ): CashReconciliationSnapshot {
        val accounts = ledgerDao.ensureDemoChartOfAccountsSeeded(nowEpochMs)
        val targets = getAllocatableTargetsForRound(roundId)
        val payments = getPaymentSnapshotsForRound(roundId)
        val journals = getLedgerJournalSnapshotsForRound(roundId, nowEpochMs)

        // 1. ยอดสั่งซื้อบิลที่ยืนยันแล้ว (Active Order Amount) เทียบกับ เงินสดที่เก็บได้จริงและจัดสรรแล้ว
        val totalActiveOrderAmountMinor = ExactMoneyMath.checkedSum(
            targets.billReceivables.map { it.orderAmountMinor }
        )
        val totalAllocatedToBillsMinor = ExactMoneyMath.checkedSum(
            targets.billReceivables.map { it.collectedAllocatedMinor }
        )
        val totalUncollectedBillReceivableMinor = ExactMoneyMath.checkedSubtract(
            totalActiveOrderAmountMinor,
            totalAllocatedToBillsMinor
        )

        // 2. ยอดสิทธิได้รับรางวัล (Winning Entitlement) เทียบกับ เงินสดที่จ่ายรางวัลจริงและจัดสรรแล้ว
        val totalWinningEntitlementMinor = ExactMoneyMath.checkedSum(
            targets.winningEntitlements.map { it.entitlementPayoutMinor }
        )
        val totalAllocatedToEntitlementsMinor = ExactMoneyMath.checkedSum(
            targets.winningEntitlements.map { it.paidAllocatedMinor }
        )
        val totalUnpaidEntitlementPayableMinor = ExactMoneyMath.checkedSubtract(
            totalWinningEntitlementMinor,
            totalAllocatedToEntitlementsMinor
        )

        // 3. กระแสเงินสดรับจริง (Inflow Collection) และกระแสเงินสดจ่ายจริง (Outflow Entitlement Payout)
        val inflowPayments = payments.filter {
            it.paymentDirection == LedgerAndAccountingDao.PAYMENT_DIRECTION_INFLOW_COLLECTION
        }
        val outflowPayments = payments.filter {
            it.paymentDirection == LedgerAndAccountingDao.PAYMENT_DIRECTION_OUTFLOW_ENTITLEMENT
        }

        val totalRecordedCashInflowMinor = ExactMoneyMath.checkedSum(
            inflowPayments.map { it.amountMinor }
        )
        val totalUnallocatedInflowMinor = ExactMoneyMath.checkedSum(
            inflowPayments.map { it.unallocatedRemainingMinor }
        )

        val totalRecordedCashOutflowMinor = ExactMoneyMath.checkedSum(
            outflowPayments.map { it.amountMinor }
        )
        val totalUnallocatedOutflowMinor = ExactMoneyMath.checkedSum(
            outflowPayments.map { it.unallocatedRemainingMinor }
        )

        // 4. คำนวณเงินสดคงเหลือตามบัญชี (Expected Book Closing Cash) และผลต่างจากเงินสดนับจริงในลิ้นชัก
        val cashPosition = PolicyAndCapEngine.evaluateCashScenarioPosition(
            openingCashMinor = openingOperatingCashMinor,
            availableInflowsMinor = totalRecordedCashInflowMinor,
            horizonDueOutflowsMinor = totalRecordedCashOutflowMinor,
            minimumRequiredCashMinor = minimumRequiredCashMinor
        )
        val expectedBookClosingCashMinor = cashPosition.closingCashMinor
        val physicalCashVarianceMinor = ExactMoneyMath.checkedSubtract(
            countedPhysicalCashMinor,
            expectedBookClosingCashMinor
        )

        // 5. คำนวณงบทดลอง (Trial Balance) ของรายการบัญชีทั้งหมดในงวด
        var totalLedgerDebitMinor = 0L
        var totalLedgerCreditMinor = 0L
        val debitByAccount = mutableMapOf<String, Long>()
        val creditByAccount = mutableMapOf<String, Long>()

        for (journal in journals) {
            for (line in journal.lines) {
                if (line.direction == LedgerEntryDirection.DEBIT.name) {
                    totalLedgerDebitMinor = ExactMoneyMath.checkedAdd(totalLedgerDebitMinor, line.amountMinor)
                    debitByAccount[line.accountId] = ExactMoneyMath.checkedAdd(
                        debitByAccount[line.accountId] ?: 0L,
                        line.amountMinor
                    )
                } else {
                    totalLedgerCreditMinor = ExactMoneyMath.checkedAdd(totalLedgerCreditMinor, line.amountMinor)
                    creditByAccount[line.accountId] = ExactMoneyMath.checkedAdd(
                        creditByAccount[line.accountId] ?: 0L,
                        line.amountMinor
                    )
                }
            }
        }

        val accountBalances = accounts.map { acc ->
            val debitMinor = debitByAccount[acc.accountId] ?: 0L
            val creditMinor = creditByAccount[acc.accountId] ?: 0L
            val netDebitMinusCreditMinor = ExactMoneyMath.checkedSubtract(debitMinor, creditMinor)
            LedgerAccountTrialBalanceLine(
                accountId = acc.accountId,
                accountCode = acc.accountCode,
                accountNameTh = acc.accountNameTh,
                accountScope = acc.accountScope,
                currencyCode = acc.currencyCode,
                isDemoChart = acc.isDemoChart,
                totalDebitMinor = debitMinor,
                totalCreditMinor = creditMinor,
                netDebitMinusCreditMinor = netDebitMinusCreditMinor
            )
        }

        return CashReconciliationSnapshot(
            roundId = roundId,
            isDemoChartOfAccounts = accounts.any { it.isDemoChart },
            openingOperatingCashMinor = openingOperatingCashMinor,
            totalRecordedCashInflowMinor = totalRecordedCashInflowMinor,
            totalRecordedCashOutflowMinor = totalRecordedCashOutflowMinor,
            expectedBookClosingCashMinor = expectedBookClosingCashMinor,
            countedPhysicalCashMinor = countedPhysicalCashMinor,
            physicalCashVarianceMinor = physicalCashVarianceMinor,
            minimumRequiredCashMinor = minimumRequiredCashMinor,
            shortfallMinor = cashPosition.shortfallMinor,
            hasShortfallAlert = cashPosition.hasShortfallAlert,
            totalActiveOrderAmountMinor = totalActiveOrderAmountMinor,
            totalAllocatedToBillsMinor = totalAllocatedToBillsMinor,
            totalUncollectedBillReceivableMinor = totalUncollectedBillReceivableMinor,
            totalUnallocatedInflowMinor = totalUnallocatedInflowMinor,
            totalWinningEntitlementMinor = totalWinningEntitlementMinor,
            totalAllocatedToEntitlementsMinor = totalAllocatedToEntitlementsMinor,
            totalUnpaidEntitlementPayableMinor = totalUnpaidEntitlementPayableMinor,
            totalUnallocatedOutflowMinor = totalUnallocatedOutflowMinor,
            totalLedgerDebitMinor = totalLedgerDebitMinor,
            totalLedgerCreditMinor = totalLedgerCreditMinor,
            isTrialBalanceBalanced = (totalLedgerDebitMinor == totalLedgerCreditMinor),
            accountTrialBalances = accountBalances
        )
    }

    // =========================================================================
    // Phase 09 — Risk Gross/Net over 100×100 Valid Outcomes, Cash Scenarios & Stale Detection
    // =========================================================================

    suspend fun computeLatestRiskMutationEpochMs(roundId: String): Long {
        val round = commitDao.getRoundById(roundId)
        val roundUpdated = round?.updatedAtEpochMs ?: 0L
        val policies = commitDao.getPolicyHistoryForRound(roundId)
        val policyLatest = policies.maxOfOrNull { it.createdAtEpochMs } ?: 0L
        val bills = commitDao.searchBillsInRound(roundId, "")
        val billLatest = bills.maxOfOrNull { it.updatedAtEpochMs } ?: 0L
        val batches = ledgerDao.getTransferBatchesForRound(roundId)
        val batchLatest = batches.maxOfOrNull { it.createdAtEpochMs } ?: 0L
        val responses = ledgerDao.getResponsesForRound(roundId)
        val responseLatest = responses.maxOfOrNull { it.recordedAtEpochMs } ?: 0L

        return maxOf(roundUpdated, policyLatest, billLatest, batchLatest, responseLatest)
    }

    suspend fun isRiskSnapshotStale(
        roundId: String,
        snapshotComputedAsOfEpochMs: Long
    ): Boolean {
        val latestMutationMs = computeLatestRiskMutationEpochMs(roundId)
        return latestMutationMs > snapshotComputedAsOfEpochMs
    }

    suspend fun computeRoundRiskAndCashDashboardBundle(
        roundId: String,
        openingCashMinor: Long,
        minimumRequiredCashMinor: Long,
        computedAsOfEpochMs: Long = System.currentTimeMillis()
    ): RoundRiskAndCashDashboardBundle {
        val round = commitDao.getRoundById(roundId)
        val policyVersion = round?.activePolicyVersion ?: 1L
        val policyEntity = commitDao.getPolicyVersion(roundId, policyVersion)
        val defaultPayoutCapMinor = policyEntity?.defaultPayoutCapMinor ?: 6_000_000L

        val overrides = commitDao.getOverridesForPolicyVersion(roundId, policyVersion)
        val payoutCapOverridesByKey = mutableMapOf<Pair<String, BetSide>, Long>()
        for (ov in overrides) {
            val cap = ov.overridePayoutCapMinor
            if (cap != null && cap > 0L) {
                val side = BetSide.valueOf(ov.side)
                payoutCapOverridesByKey[ov.numberTwoDigit to side] = cap
            }
        }

        val activeBills = commitDao.searchBillsInRound(roundId, "").filter {
            it.status == BillStatus.ACTIVE.name && it.activeRevisionId != null
        }

        val activeBillLines = mutableListOf<RiskActiveBillLineInput>()
        for (bill in activeBills) {
            val revId = bill.activeRevisionId ?: continue
            val lines = commitDao.getLinesForRevision(revId)
            for (line in lines) {
                activeBillLines.add(
                    RiskActiveBillLineInput(
                        numberTwoDigit = line.numberTwoDigit,
                        side = BetSide.valueOf(line.side),
                        amountMinor = line.amountMinor,
                        provisionalPayoutMinor = line.provisionalPayoutMinor
                    )
                )
            }
        }

        val batchesById = ledgerDao.getTransferBatchesForRound(roundId).associateBy { it.batchId }
        val rawTransferLines = ledgerDao.getTransferLinesForRound(roundId)
        val transferLineInputs = rawTransferLines.mapNotNull { tLine ->
            val batch = batchesById[tLine.batchId] ?: return@mapNotNull null
            RiskTransferLineInput(
                batchId = tLine.batchId,
                batchStatus = batch.status,
                lineStatus = tLine.lineStatus,
                numberTwoDigit = tLine.numberTwoDigit,
                side = BetSide.valueOf(tLine.side),
                submittedAmountMinor = tLine.submittedAmountMinor,
                acceptedAmountMinor = tLine.acceptedAmountMinor,
                acceptedRecoverablePayoutMinor = tLine.acceptedRecoverablePayoutMinor
            )
        }

        val latestMutationMs = computeLatestRiskMutationEpochMs(roundId)
        val effectiveAsOfMs = maxOf(computedAsOfEpochMs, latestMutationMs)

        val riskSnapshot = RiskOutcomeAndCashScenarioEngine.evaluateValidOutcomesRiskMaxima(
            roundId = roundId,
            policyVersion = policyVersion,
            defaultTopPayoutCapMinor = defaultPayoutCapMinor,
            defaultBottomPayoutCapMinor = defaultPayoutCapMinor,
            payoutCapOverridesByKey = payoutCapOverridesByKey,
            activeBillLines = activeBillLines,
            transferLines = transferLineInputs,
            computedAsOfEpochMs = effectiveAsOfMs,
            latestDataMutationEpochMs = latestMutationMs
        )

        val recon = getCashReconciliationSnapshot(
            roundId = roundId,
            openingOperatingCashMinor = openingCashMinor,
            countedPhysicalCashMinor = openingCashMinor,
            minimumRequiredCashMinor = minimumRequiredCashMinor,
            nowEpochMs = effectiveAsOfMs
        )

        val cashScenarios = RiskOutcomeAndCashScenarioEngine.evaluateRoundCashScenarios(
            openingCashMinor = openingCashMinor,
            collectedCashInflowsMinor = recon.totalRecordedCashInflowMinor,
            uncollectedBillReceivableMinor = recon.totalUncollectedBillReceivableMinor,
            paidOutflowsMinor = recon.totalRecordedCashOutflowMinor,
            minimumRequiredCashMinor = minimumRequiredCashMinor,
            worstGrossOutcomePayoutMinor = riskSnapshot.worstGrossJointOutcome.jointGrossPayoutMinor,
            worstNetOutcomePayoutMinor = riskSnapshot.worstNetJointOutcome.jointNetPayoutMinor
        )

        return RoundRiskAndCashDashboardBundle(
            roundId = roundId,
            roundCode = round?.roundCode ?: roundId,
            activeBillCount = activeBills.size,
            activeLineCount = activeBillLines.size,
            riskSnapshot = riskSnapshot,
            cashScenarios = cashScenarios
        )
    }

    // =========================================================================
    // Phase 10 — Counterparty & Manual Transfer Batches (Pending / Accepted / Partial / Rejected)
    // =========================================================================

    suspend fun ensureDemoCounterpartyAndContractSeeded(
        roundId: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Pair<CounterpartyEntity, CounterpartyContractEntity> {
        return ledgerDao.ensureDemoCounterpartyAndContractSeeded(roundId, nowEpochMs)
    }

    suspend fun upsertCounterpartyAndContract(
        counterpartyId: String,
        counterpartyCode: String,
        displayNameTh: String,
        contractId: String,
        roundId: String,
        contractTopRateExact: String,
        contractBottomRateExact: String,
        maxTransferCapMinor: Long,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Pair<CounterpartyEntity, CounterpartyContractEntity> {
        val cp = CounterpartyEntity(
            counterpartyId = counterpartyId,
            counterpartyCode = counterpartyCode.trim(),
            displayNameTh = displayNameTh.trim(),
            isActive = true,
            createdAtEpochMs = nowEpochMs
        )
        val contract = CounterpartyContractEntity(
            contractId = contractId,
            counterpartyId = cp.counterpartyId,
            roundId = roundId,
            contractTopRateExact = contractTopRateExact.trim(),
            contractBottomRateExact = contractBottomRateExact.trim(),
            maxTransferCapMinor = maxTransferCapMinor,
            createdAtEpochMs = nowEpochMs
        )
        ledgerDao.upsertCounterparty(cp)
        ledgerDao.upsertCounterpartyContract(contract)
        return cp to contract
    }

    suspend fun createManualTransferBatchPending(
        batchId: String,
        roundId: String,
        contractId: String,
        draftLines: List<ManualTransferLineDraft>,
        actorId: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): ManualTransferBatchCreateResult {
        return ledgerDao.createManualTransferBatchPendingTransaction(
            batchId = batchId,
            roundId = roundId,
            contractId = contractId,
            draftLines = draftLines,
            actorId = actorId,
            nowEpochMs = nowEpochMs
        )
    }

    suspend fun recordManualTransferResponse(
        responseId: String,
        batchId: String,
        responseStatus: TransferResponseStatus,
        partialAcceptedMinorByKey: Map<Pair<String, BetSide>, Long> = emptyMap(),
        manualReferenceNoteTh: String,
        settlementLedgerTxId: String?,
        settlementOperationKey: String?,
        actorId: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): ManualTransferResponseResult {
        return ledgerDao.recordManualTransferResponseTransaction(
            responseId = responseId,
            batchId = batchId,
            responseStatus = responseStatus,
            partialAcceptedMinorByKey = partialAcceptedMinorByKey,
            manualReferenceNoteTh = manualReferenceNoteTh,
            settlementLedgerTxId = settlementLedgerTxId,
            settlementOperationKey = settlementOperationKey,
            actorId = actorId,
            nowEpochMs = nowEpochMs
        )
    }

    suspend fun getCounterpartyTransferRoundSnapshot(
        roundId: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): CounterpartyTransferRoundSnapshot {
        ledgerDao.ensureDemoCounterpartyAndContractSeeded(roundId, nowEpochMs)
        val counterpartiesById = ledgerDao.getAllCounterparties().associateBy { it.counterpartyId }
        val contracts = ledgerDao.getContractsForRound(roundId)
        val contractsById = contracts.associateBy { it.contractId }
        val batches = ledgerDao.getTransferBatchesForRound(roundId)

        var totalPendingSubmittedMinor = 0L
        var totalAcceptedPrincipalMinor = 0L
        var totalAcceptedRecoverableCeilingMinor = 0L

        val batchSnapshots = batches.map { batch ->
            val lines = ledgerDao.getTransferLinesForBatch(batch.batchId)
            val responses = ledgerDao.getResponsesForBatch(batch.batchId)
            val contract = contractsById[batch.contractId]
            val cp = contract?.let { counterpartiesById[it.counterpartyId] }

            val batchRecoverableCeilingMinor = ExactMoneyMath.checkedSum(
                lines.map { it.acceptedRecoverablePayoutMinor }
            )

            if (batch.status == TransferResponseStatus.PENDING.name) {
                totalPendingSubmittedMinor = ExactMoneyMath.checkedAdd(
                    totalPendingSubmittedMinor,
                    batch.totalSubmittedMinor
                )
            } else if (batch.status == TransferResponseStatus.ACCEPTED.name ||
                batch.status == TransferResponseStatus.PARTIAL.name
            ) {
                totalAcceptedPrincipalMinor = ExactMoneyMath.checkedAdd(
                    totalAcceptedPrincipalMinor,
                    batch.totalAcceptedMinor
                )
                totalAcceptedRecoverableCeilingMinor = ExactMoneyMath.checkedAdd(
                    totalAcceptedRecoverableCeilingMinor,
                    batchRecoverableCeilingMinor
                )
            }

            ManualTransferBatchDetailSnapshot(
                batchId = batch.batchId,
                roundId = batch.roundId,
                contractId = batch.contractId,
                counterpartyNameTh = cp?.displayNameTh ?: batch.contractId,
                status = batch.status,
                totalSubmittedMinor = batch.totalSubmittedMinor,
                totalAcceptedMinor = batch.totalAcceptedMinor,
                totalAcceptedRecoverableCeilingMinor = batchRecoverableCeilingMinor,
                createdAtEpochMs = batch.createdAtEpochMs,
                lines = lines,
                responses = responses
            )
        }

        val counterpartyJournals = getLedgerJournalSnapshotsForRound(roundId, nowEpochMs)
            .filter { it.accountScope == com.oneproject.offline.data.local.entity.AccountScope.COUNTERPARTY_SETTLEMENT.name }

        return CounterpartyTransferRoundSnapshot(
            roundId = roundId,
            counterparties = counterpartiesById.values.toList(),
            contracts = contracts,
            batches = batchSnapshots,
            totalPendingSubmittedMinor = totalPendingSubmittedMinor,
            pendingExposureReductionMinor = 0L, // กฎเหล็ก: Pending ลดความเสี่ยงได้ 0 เสมอ
            totalAcceptedPrincipalMinor = totalAcceptedPrincipalMinor,
            totalAcceptedRecoverableCeilingMinor = totalAcceptedRecoverableCeilingMinor,
            counterpartySettlementJournals = counterpartyJournals,
            isExternalExecutionDisabled = true
        )
    }
}

data class RoundRiskAndCashDashboardBundle(
    val roundId: String,
    val roundCode: String,
    val activeBillCount: Int,
    val activeLineCount: Int,
    val riskSnapshot: RoundRiskAssessmentSnapshot,
    val cashScenarios: RoundCashScenariosComparison
)

data class ManualTransferBatchDetailSnapshot(
    val batchId: String,
    val roundId: String,
    val contractId: String,
    val counterpartyNameTh: String,
    val status: String,
    val totalSubmittedMinor: Long,
    val totalAcceptedMinor: Long,
    val totalAcceptedRecoverableCeilingMinor: Long,
    val createdAtEpochMs: Long,
    val lines: List<ManualTransferLineEntity>,
    val responses: List<TransferResponseEntity>
)

data class CounterpartyTransferRoundSnapshot(
    val roundId: String,
    val counterparties: List<CounterpartyEntity>,
    val contracts: List<CounterpartyContractEntity>,
    val batches: List<ManualTransferBatchDetailSnapshot>,
    val totalPendingSubmittedMinor: Long,
    val pendingExposureReductionMinor: Long,
    val totalAcceptedPrincipalMinor: Long,
    val totalAcceptedRecoverableCeilingMinor: Long,
    val counterpartySettlementJournals: List<LedgerJournalSnapshot>,
    val isExternalExecutionDisabled: Boolean
)

data class LedgerEntryLineSnapshot(
    val entrySequence: Int,
    val accountId: String,
    val accountCode: String,
    val accountNameTh: String,
    val direction: String,
    val amountMinor: Long,
    val currencyCode: String,
    val accountScope: String
)

data class LedgerJournalSnapshot(
    val transactionId: String,
    val operationKey: String,
    val roundId: String?,
    val currencyCode: String,
    val accountScope: String,
    val totalBalancedMinor: Long,
    val reversesTransactionId: String?,
    val reversedByTransactionId: String?,
    val memoTh: String,
    val postedByActorId: String,
    val postedAtEpochMs: Long,
    val lines: List<LedgerEntryLineSnapshot>
)

data class PaymentRecordSnapshot(
    val paymentId: String,
    val roundId: String,
    val paymentDirection: String,
    val amountMinor: Long,
    val allocatedTotalMinor: Long,
    val unallocatedRemainingMinor: Long,
    val ledgerTransactionId: String?,
    val evidenceNoteTh: String,
    val recordedAtEpochMs: Long,
    val allocations: List<PaymentAllocationEntity>
)

data class AllocatableBillReceivableTarget(
    val billId: String,
    val humanBillCode: String,
    val customerNoteTh: String,
    val orderAmountMinor: Long,
    val collectedAllocatedMinor: Long,
    val uncollectedRemainingMinor: Long
)

data class AllocatableWinningEntitlementTarget(
    val entitlementId: String,
    val resultRevisionId: String,
    val billId: String,
    val humanBillCode: String,
    val billLineId: Long,
    val entitlementPayoutMinor: Long,
    val paidAllocatedMinor: Long,
    val unpaidRemainingMinor: Long
)

data class AllocatableTargetsSnapshot(
    val roundId: String,
    val activeResultRevisionId: String?,
    val billReceivables: List<AllocatableBillReceivableTarget>,
    val winningEntitlements: List<AllocatableWinningEntitlementTarget>
)

data class LedgerAccountTrialBalanceLine(
    val accountId: String,
    val accountCode: String,
    val accountNameTh: String,
    val accountScope: String,
    val currencyCode: String,
    val isDemoChart: Boolean,
    val totalDebitMinor: Long,
    val totalCreditMinor: Long,
    val netDebitMinusCreditMinor: Long
)

data class CashReconciliationSnapshot(
    val roundId: String,
    val isDemoChartOfAccounts: Boolean,
    val openingOperatingCashMinor: Long,
    val totalRecordedCashInflowMinor: Long,
    val totalRecordedCashOutflowMinor: Long,
    val expectedBookClosingCashMinor: Long,
    val countedPhysicalCashMinor: Long,
    val physicalCashVarianceMinor: Long,
    val minimumRequiredCashMinor: Long,
    val shortfallMinor: Long,
    val hasShortfallAlert: Boolean,
    val totalActiveOrderAmountMinor: Long,
    val totalAllocatedToBillsMinor: Long,
    val totalUncollectedBillReceivableMinor: Long,
    val totalUnallocatedInflowMinor: Long,
    val totalWinningEntitlementMinor: Long,
    val totalAllocatedToEntitlementsMinor: Long,
    val totalUnpaidEntitlementPayableMinor: Long,
    val totalUnallocatedOutflowMinor: Long,
    val totalLedgerDebitMinor: Long,
    val totalLedgerCreditMinor: Long,
    val isTrialBalanceBalanced: Boolean,
    val accountTrialBalances: List<LedgerAccountTrialBalanceLine>
)
