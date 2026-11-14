package com.oneproject.offline.ui.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.oneproject.offline.core.money.ExactMoneyMath
import com.oneproject.offline.data.local.dao.LedgerAndAccountingDao
import com.oneproject.offline.data.local.dao.LedgerEntryDraft
import com.oneproject.offline.data.local.dao.LedgerPostingResult
import com.oneproject.offline.data.local.dao.PaymentAllocationResult
import com.oneproject.offline.data.local.dao.PaymentRecordResult
import com.oneproject.offline.data.local.entity.AccountScope
import com.oneproject.offline.data.local.entity.LedgerAccountEntity
import com.oneproject.offline.data.local.entity.LedgerEntryDirection
import com.oneproject.offline.data.repository.AllocatableBillReceivableTarget
import com.oneproject.offline.data.repository.AllocatableWinningEntitlementTarget
import com.oneproject.offline.data.repository.CashReconciliationSnapshot
import com.oneproject.offline.data.repository.LedgerAndReconciliationRepository
import com.oneproject.offline.data.repository.LedgerJournalSnapshot
import com.oneproject.offline.data.repository.OfflineBootstrapRepository
import com.oneproject.offline.data.repository.PaymentRecordSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class LedgerSubTab(val titleTh: String) {
    JOURNAL_AND_REVERSAL("1. สมุดบัญชีคู่ & กลับรายการ"),
    PAYMENT_AND_ALLOCATION("2. รับ/จ่ายเงิน & จัดสรรยอด"),
    CASH_RECONCILIATION("3. กระทบยอดเงินสด & งบทดลอง")
}

data class LedgerAndReconciliationUiState(
    val roundId: String = "",
    val roundCode: String = "",
    val selectedTab: LedgerSubTab = LedgerSubTab.JOURNAL_AND_REVERSAL,
    val isDemoChartOfAccounts: Boolean = true,
    val accounts: List<LedgerAccountEntity> = emptyList(),

    // Tab 1: Manual Double-Entry Journal & Linked Reversal
    val selectedScopeName: String = AccountScope.ORDER_SETTLEMENT.name,
    val selectedDebitAccountId: String = LedgerAndAccountingDao.DEMO_ACC_ORDER_CASH,
    val selectedCreditAccountId: String = LedgerAndAccountingDao.DEMO_ACC_BILL_RECEIVABLE,
    val manualAmountMajorInput: String = "100.00",
    val manualMemoThInput: String = "",
    val selectedOriginalTxIdForReversal: String? = null,
    val reversalReasonThInput: String = "",
    val journals: List<LedgerJournalSnapshot> = emptyList(),

    // Tab 2: Payment Record & Bounded Allocation
    val selectedPaymentDirection: String = LedgerAndAccountingDao.PAYMENT_DIRECTION_INFLOW_COLLECTION,
    val paymentAmountMajorInput: String = "100.00",
    val paymentEvidenceNoteThInput: String = "",
    val payments: List<PaymentRecordSnapshot> = emptyList(),
    val billReceivableTargets: List<AllocatableBillReceivableTarget> = emptyList(),
    val winningEntitlementTargets: List<AllocatableWinningEntitlementTarget> = emptyList(),
    val selectedPaymentIdForAllocation: String? = null,
    val selectedAllocationTargetType: String = LedgerAndAccountingDao.TARGET_TYPE_BILL_RECEIVABLE,
    val selectedAllocationTargetId: String? = null,
    val allocationAmountMajorInput: String = "50.00",

    // Tab 3: Cash & Settlement Reconciliation
    val openingCashMajorInput: String = "5000.00",
    val countedPhysicalCashMajorInput: String = "5000.00",
    val minimumRequiredCashMajorInput: String = "2000.00",
    val reconciliationSnapshot: CashReconciliationSnapshot? = null,

    // Banners
    val statusBannerTh: String? = null,
    val errorBannerTh: String? = null
)

/**
 * Deterministic Controller สำหรับ Phase 08 (สมุดบัญชีคู่, การกลับรายการแบบผูกอ้างอิง,
 * การรับ/จ่ายและจัดสรรยอดไม่เกินเพดานจริง, และการกระทบยอดเงินสดในเครื่อง)
 */
class LedgerAndReconciliationWorkflowController(
    private val bootstrapRepository: OfflineBootstrapRepository,
    private val ledgerRepository: LedgerAndReconciliationRepository,
    private val clockEpochMsProvider: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: (String) -> String = { prefix ->
        "$prefix-${UUID.randomUUID().toString().take(8).uppercase()}"
    }
) {
    private val _uiState = MutableStateFlow(LedgerAndReconciliationUiState())
    val uiState: StateFlow<LedgerAndReconciliationUiState> = _uiState.asStateFlow()

    suspend fun initializeAndRefresh() {
        val nowMs = clockEpochMsProvider()
        val status = bootstrapRepository.getCurrentShellStatus()
        val roundId = status.activeRound?.roundId ?: OfflineBootstrapRepository.DEFAULT_ROUND_ID
        val roundCode = status.activeRound?.roundCode ?: "2026-04-16-DEMO"

        val accounts = ledgerRepository.ensureDemoChartOfAccountsSeeded(nowMs)
        _uiState.update {
            it.copy(
                roundId = roundId,
                roundCode = roundCode,
                isDemoChartOfAccounts = accounts.any { acc -> acc.isDemoChart },
                accounts = accounts
            )
        }
        refreshAllSnapshots(roundId, nowMs)
    }

    fun selectTab(tab: LedgerSubTab) {
        _uiState.update {
            it.copy(
                selectedTab = tab,
                errorBannerTh = null
            )
        }
    }

    fun updateManualEntryDraft(
        scopeName: String? = null,
        debitAccountId: String? = null,
        creditAccountId: String? = null,
        amountMajorInput: String? = null,
        memoThInput: String? = null
    ) {
        _uiState.update { current ->
            val nextScope = scopeName ?: current.selectedScopeName
            val scopedAccounts = current.accounts.filter { it.accountScope == nextScope }
            val nextDebit = debitAccountId ?: scopedAccounts.firstOrNull()?.accountId ?: current.selectedDebitAccountId
            val nextCredit = creditAccountId ?: scopedAccounts.getOrNull(1)?.accountId ?: scopedAccounts.firstOrNull()?.accountId ?: current.selectedCreditAccountId
            current.copy(
                selectedScopeName = nextScope,
                selectedDebitAccountId = nextDebit,
                selectedCreditAccountId = nextCredit,
                manualAmountMajorInput = amountMajorInput ?: current.manualAmountMajorInput,
                manualMemoThInput = memoThInput ?: current.manualMemoThInput,
                errorBannerTh = null
            )
        }
    }

    fun selectTransactionForReversal(transactionId: String?, reversalReasonTh: String? = null) {
        _uiState.update { current ->
            current.copy(
                selectedOriginalTxIdForReversal = transactionId,
                reversalReasonThInput = reversalReasonTh ?: current.reversalReasonThInput,
                errorBannerTh = null
            )
        }
    }

    fun updatePaymentDraft(
        paymentDirection: String? = null,
        amountMajorInput: String? = null,
        evidenceNoteThInput: String? = null
    ) {
        _uiState.update { current ->
            val nextDirection = paymentDirection ?: current.selectedPaymentDirection
            val matchingTargetType = if (nextDirection == LedgerAndAccountingDao.PAYMENT_DIRECTION_INFLOW_COLLECTION) {
                LedgerAndAccountingDao.TARGET_TYPE_BILL_RECEIVABLE
            } else {
                LedgerAndAccountingDao.TARGET_TYPE_WINNING_ENTITLEMENT
            }
            current.copy(
                selectedPaymentDirection = nextDirection,
                paymentAmountMajorInput = amountMajorInput ?: current.paymentAmountMajorInput,
                paymentEvidenceNoteThInput = evidenceNoteThInput ?: current.paymentEvidenceNoteThInput,
                selectedAllocationTargetType = matchingTargetType,
                errorBannerTh = null
            )
        }
    }

    fun updateAllocationDraft(
        paymentId: String? = null,
        targetType: String? = null,
        targetId: String? = null,
        allocationAmountMajorInput: String? = null
    ) {
        _uiState.update { current ->
            current.copy(
                selectedPaymentIdForAllocation = paymentId ?: current.selectedPaymentIdForAllocation,
                selectedAllocationTargetType = targetType ?: current.selectedAllocationTargetType,
                selectedAllocationTargetId = targetId ?: current.selectedAllocationTargetId,
                allocationAmountMajorInput = allocationAmountMajorInput ?: current.allocationAmountMajorInput,
                errorBannerTh = null
            )
        }
    }

    fun fillMaxRemainingAllocationForSelection() {
        val state = _uiState.value
        val payment = state.payments.find { it.paymentId == state.selectedPaymentIdForAllocation }
        val targetRemainingMinor = when (state.selectedAllocationTargetType) {
            LedgerAndAccountingDao.TARGET_TYPE_BILL_RECEIVABLE ->
                state.billReceivableTargets.find { it.billId == state.selectedAllocationTargetId }?.uncollectedRemainingMinor
            LedgerAndAccountingDao.TARGET_TYPE_WINNING_ENTITLEMENT ->
                state.winningEntitlementTargets.find { it.entitlementId == state.selectedAllocationTargetId }?.unpaidRemainingMinor
            else -> null
        }

        if (payment == null || targetRemainingMinor == null) {
            _uiState.update {
                it.copy(errorBannerTh = "กรุณาเลือกรายการรับ/จ่ายเงินสด และเป้าหมายบิล/สิทธิรางวัลก่อนคำนวณเพดานจัดสรร")
            }
            return
        }

        val maxBoundedMinor = minOf(payment.unallocatedRemainingMinor, targetRemainingMinor)
        if (maxBoundedMinor <= 0L) {
            _uiState.update {
                it.copy(errorBannerTh = "รายการที่เลือกไม่มียอดคงเหลือให้จัดสรรเพิ่มเติม (0.00 บาท)")
            }
            return
        }

        _uiState.update {
            it.copy(
                allocationAmountMajorInput = ExactMoneyMath.formatMinorToThbString(maxBoundedMinor),
                errorBannerTh = null
            )
        }
    }

    fun updateCashReconciliationInputs(
        openingCashMajorInput: String? = null,
        countedPhysicalCashMajorInput: String? = null,
        minimumRequiredCashMajorInput: String? = null
    ) {
        _uiState.update { current ->
            current.copy(
                openingCashMajorInput = openingCashMajorInput ?: current.openingCashMajorInput,
                countedPhysicalCashMajorInput = countedPhysicalCashMajorInput ?: current.countedPhysicalCashMajorInput,
                minimumRequiredCashMajorInput = minimumRequiredCashMajorInput ?: current.minimumRequiredCashMajorInput,
                errorBannerTh = null
            )
        }
    }

    suspend fun recalculateCashReconciliation() {
        val state = _uiState.value
        if (state.roundId.isBlank()) return
        refreshAllSnapshots(state.roundId, clockEpochMsProvider())
    }

    /**
     * โพสต์รายการบัญชีคู่สมดุลด้วยมือ (Manual Balanced Double-Entry Journal)
     */
    suspend fun postManualBalancedJournal(
        customOperationKey: String? = null,
        actorId: String = "LOCAL_ACCOUNTANT_01"
    ): LedgerPostingResult {
        val state = _uiState.value
        val amountMinor = try {
            ExactMoneyMath.parseMajorStringToMinor(state.manualAmountMajorInput)
        } catch (e: IllegalArgumentException) {
            val reason = "จำนวนเงินไม่ถูกต้อง: ${e.message}"
            _uiState.update { it.copy(errorBannerTh = reason, statusBannerTh = null) }
            return LedgerPostingResult.Rejected(reason)
        }

        if (state.selectedDebitAccountId == state.selectedCreditAccountId) {
            val reason = "บัญชีเดบิตและบัญชีเครดิตต้องเป็นคนละรหัสบัญชีกัน"
            _uiState.update { it.copy(errorBannerTh = reason, statusBannerTh = null) }
            return LedgerPostingResult.Rejected(reason)
        }

        val nowMs = clockEpochMsProvider()
        val txId = idGenerator("LTX")
        val opKey = customOperationKey ?: idGenerator("OP-LTX")

        val entries = listOf(
            LedgerEntryDraft(
                accountId = state.selectedDebitAccountId,
                direction = LedgerEntryDirection.DEBIT,
                amountMinor = amountMinor
            ),
            LedgerEntryDraft(
                accountId = state.selectedCreditAccountId,
                direction = LedgerEntryDirection.CREDIT,
                amountMinor = amountMinor
            )
        )

        val result = ledgerRepository.postManualBalancedTransaction(
            transactionId = txId,
            operationKey = opKey,
            roundId = state.roundId,
            currencyCode = "THB",
            accountScope = state.selectedScopeName,
            memoTh = state.manualMemoThInput,
            postedByActorId = actorId,
            entries = entries,
            nowEpochMs = nowMs
        )

        when (result) {
            is LedgerPostingResult.Posted -> {
                refreshAllSnapshots(state.roundId, nowMs)
                _uiState.update {
                    it.copy(
                        manualMemoThInput = "",
                        statusBannerTh = "โพสต์รายการบัญชีคู่สำเร็จ: ${result.transactionId} ยอดสมดุล ${ExactMoneyMath.formatMinorToThbString(result.totalBalancedMinor)} บาท",
                        errorBannerTh = null
                    )
                }
            }
            is LedgerPostingResult.Rejected -> {
                _uiState.update {
                    it.copy(
                        errorBannerTh = result.reasonTh,
                        statusBannerTh = null
                    )
                }
            }
        }
        return result
    }

    /**
     * สร้างรายการกลับบัญชีแบบผูกอ้างอิง (Linked Reversal — ห้ามแก้ทับรายการที่โพสต์แล้ว)
     */
    suspend fun postLinkedReversal(
        originalTransactionId: String? = _uiState.value.selectedOriginalTxIdForReversal,
        reversalReasonTh: String = _uiState.value.reversalReasonThInput,
        customOperationKey: String? = null,
        actorId: String = "LOCAL_ACCOUNTANT_01"
    ): LedgerPostingResult {
        val state = _uiState.value
        val targetTxId = originalTransactionId?.trim().orEmpty()
        if (targetTxId.isBlank()) {
            val reason = "กรุณาเลือกรายการบัญชีต้นทางที่ต้องการกลับรายการ"
            _uiState.update { it.copy(errorBannerTh = reason, statusBannerTh = null) }
            return LedgerPostingResult.Rejected(reason)
        }

        val nowMs = clockEpochMsProvider()
        val reversalTxId = idGenerator("REV")
        val opKey = customOperationKey ?: idGenerator("OP-REV")

        val result = ledgerRepository.postLinkedReversalTransaction(
            newReversalTransactionId = reversalTxId,
            operationKey = opKey,
            originalTransactionId = targetTxId,
            reversalReasonTh = reversalReasonTh,
            postedByActorId = actorId,
            nowEpochMs = nowMs
        )

        when (result) {
            is LedgerPostingResult.Posted -> {
                refreshAllSnapshots(state.roundId, nowMs)
                _uiState.update {
                    it.copy(
                        selectedOriginalTxIdForReversal = null,
                        reversalReasonThInput = "",
                        statusBannerTh = "สร้างรายการกลับบัญชีสำเร็จ: ${result.transactionId} (อ้างอิงกลับรายการ $targetTxId)",
                        errorBannerTh = null
                    )
                }
            }
            is LedgerPostingResult.Rejected -> {
                _uiState.update {
                    it.copy(
                        errorBannerTh = result.reasonTh,
                        statusBannerTh = null
                    )
                }
            }
        }
        return result
    }

    /**
     * บันทึกรับ/จ่ายเงินสดพร้อมโพสต์รายการบัญชีคู่สมดุลใน Room Transaction เดียวกัน
     */
    suspend fun recordPaymentWithJournal(
        customOperationKey: String? = null,
        actorId: String = "LOCAL_CASHIER_01"
    ): PaymentRecordResult {
        val state = _uiState.value
        val amountMinor = try {
            ExactMoneyMath.parseMajorStringToMinor(state.paymentAmountMajorInput)
        } catch (e: IllegalArgumentException) {
            val reason = "จำนวนเงินรับ/จ่ายไม่ถูกต้อง: ${e.message}"
            _uiState.update { it.copy(errorBannerTh = reason, statusBannerTh = null) }
            return PaymentRecordResult.Rejected(reason)
        }

        val nowMs = clockEpochMsProvider()
        val paymentId = idGenerator("PAY")
        val ledgerTxId = idGenerator("LTX-PAY")
        val opKey = customOperationKey ?: idGenerator("OP-PAY")

        val result = ledgerRepository.recordPaymentWithLedgerPosting(
            paymentId = paymentId,
            ledgerTransactionId = ledgerTxId,
            operationKey = opKey,
            roundId = state.roundId,
            paymentDirection = state.selectedPaymentDirection,
            amountMinor = amountMinor,
            evidenceNoteTh = state.paymentEvidenceNoteThInput,
            actorId = actorId,
            nowEpochMs = nowMs
        )

        when (result) {
            is PaymentRecordResult.Recorded -> {
                refreshAllSnapshots(state.roundId, nowMs)
                _uiState.update {
                    it.copy(
                        paymentEvidenceNoteThInput = "",
                        selectedPaymentIdForAllocation = result.paymentId,
                        statusBannerTh = "บันทึกรับ/จ่ายเงินสดสำเร็จ: ${result.paymentId} (${ExactMoneyMath.formatMinorToThbString(result.amountMinor)} บาท) • สมุดบัญชี ${result.ledgerTransactionId}",
                        errorBannerTh = null
                    )
                }
            }
            is PaymentRecordResult.Rejected -> {
                _uiState.update {
                    it.copy(
                        errorBannerTh = result.reasonTh,
                        statusBannerTh = null
                    )
                }
            }
        }
        return result
    }

    /**
     * จัดสรรยอดเงินสดเข้าบิล (BILL_RECEIVABLE) หรือสิทธิรางวัล (WINNING_ENTITLEMENT) แบบไม่เกินเพดานจริง
     */
    suspend fun allocatePaymentBounded(
        actorId: String = "LOCAL_CASHIER_01"
    ): PaymentAllocationResult {
        val state = _uiState.value
        val paymentId = state.selectedPaymentIdForAllocation
        val targetId = state.selectedAllocationTargetId

        if (paymentId.isNullOrBlank() || targetId.isNullOrBlank()) {
            val reason = "กรุณาเลือกรายการรับ/จ่ายเงินสด และเลือกรายการบิลหรือสิทธิรางวัลเป้าหมายก่อนจัดสรร"
            _uiState.update { it.copy(errorBannerTh = reason, statusBannerTh = null) }
            return PaymentAllocationResult.Rejected(reason)
        }

        val allocationMinor = try {
            ExactMoneyMath.parseMajorStringToMinor(state.allocationAmountMajorInput)
        } catch (e: IllegalArgumentException) {
            val reason = "จำนวนเงินที่ต้องการจัดสรรไม่ถูกต้อง: ${e.message}"
            _uiState.update { it.copy(errorBannerTh = reason, statusBannerTh = null) }
            return PaymentAllocationResult.Rejected(reason)
        }

        val nowMs = clockEpochMsProvider()
        val allocationId = idGenerator("ALC")

        val result = ledgerRepository.allocatePaymentToTargetBounded(
            allocationId = allocationId,
            paymentId = paymentId,
            targetType = state.selectedAllocationTargetType,
            targetId = targetId,
            allocationAmountMinor = allocationMinor,
            actorId = actorId,
            nowEpochMs = nowMs
        )

        when (result) {
            is PaymentAllocationResult.Allocated -> {
                refreshAllSnapshots(state.roundId, nowMs)
                _uiState.update {
                    it.copy(
                        statusBannerTh = "จัดสรรเงินสำเร็จ (${result.allocationId}): จัดสรร ${ExactMoneyMath.formatMinorToThbString(result.allocatedAmountMinor)} บาท เข้า ${result.targetType} (${result.targetId})",
                        errorBannerTh = null
                    )
                }
            }
            is PaymentAllocationResult.Rejected -> {
                _uiState.update {
                    it.copy(
                        errorBannerTh = result.reasonTh,
                        statusBannerTh = null
                    )
                }
            }
        }
        return result
    }

    private suspend fun refreshAllSnapshots(roundId: String, nowMs: Long) {
        val journals = ledgerRepository.getLedgerJournalSnapshotsForRound(roundId, nowMs)
        val payments = ledgerRepository.getPaymentSnapshotsForRound(roundId)
        val targets = ledgerRepository.getAllocatableTargetsForRound(roundId)

        val currentState = _uiState.value
        val openingMinor = parseMinorOrZero(currentState.openingCashMajorInput)
        val countedMinor = parseMinorOrZero(currentState.countedPhysicalCashMajorInput)
        val minRequiredMinor = parseMinorOrZero(currentState.minimumRequiredCashMajorInput)

        val reconciliation = ledgerRepository.getCashReconciliationSnapshot(
            roundId = roundId,
            openingOperatingCashMinor = openingMinor,
            countedPhysicalCashMinor = countedMinor,
            minimumRequiredCashMinor = minRequiredMinor,
            nowEpochMs = nowMs
        )

        val defaultPaymentId = currentState.selectedPaymentIdForAllocation
            ?.takeIf { id -> payments.any { it.paymentId == id } }
            ?: payments.firstOrNull()?.paymentId

        val defaultTargetId = when (currentState.selectedAllocationTargetType) {
            LedgerAndAccountingDao.TARGET_TYPE_BILL_RECEIVABLE ->
                currentState.selectedAllocationTargetId
                    ?.takeIf { id -> targets.billReceivables.any { it.billId == id } }
                    ?: targets.billReceivables.firstOrNull()?.billId
            LedgerAndAccountingDao.TARGET_TYPE_WINNING_ENTITLEMENT ->
                currentState.selectedAllocationTargetId
                    ?.takeIf { id -> targets.winningEntitlements.any { it.entitlementId == id } }
                    ?: targets.winningEntitlements.firstOrNull()?.entitlementId
            else -> null
        }

        _uiState.update {
            it.copy(
                journals = journals,
                payments = payments,
                billReceivableTargets = targets.billReceivables,
                winningEntitlementTargets = targets.winningEntitlements,
                selectedPaymentIdForAllocation = defaultPaymentId,
                selectedAllocationTargetId = defaultTargetId,
                reconciliationSnapshot = reconciliation
            )
        }
    }

    private fun parseMinorOrZero(input: String): Long {
        return try {
            ExactMoneyMath.parseMajorStringToMinor(input)
        } catch (_: IllegalArgumentException) {
            0L
        }
    }
}

class LedgerAndReconciliationViewModel(
    private val controller: LedgerAndReconciliationWorkflowController
) : ViewModel() {

    val uiState: StateFlow<LedgerAndReconciliationUiState> = controller.uiState

    init {
        viewModelScope.launch {
            controller.initializeAndRefresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            controller.initializeAndRefresh()
        }
    }

    fun selectTab(tab: LedgerSubTab) {
        controller.selectTab(tab)
    }

    fun updateManualEntryDraft(
        scopeName: String? = null,
        debitAccountId: String? = null,
        creditAccountId: String? = null,
        amountMajorInput: String? = null,
        memoThInput: String? = null
    ) {
        controller.updateManualEntryDraft(
            scopeName = scopeName,
            debitAccountId = debitAccountId,
            creditAccountId = creditAccountId,
            amountMajorInput = amountMajorInput,
            memoThInput = memoThInput
        )
    }

    fun selectTransactionForReversal(transactionId: String?, reversalReasonTh: String? = null) {
        controller.selectTransactionForReversal(transactionId, reversalReasonTh)
    }

    fun postManualBalancedJournal() {
        viewModelScope.launch {
            controller.postManualBalancedJournal()
        }
    }

    fun postLinkedReversal() {
        viewModelScope.launch {
            controller.postLinkedReversal()
        }
    }

    fun updatePaymentDraft(
        paymentDirection: String? = null,
        amountMajorInput: String? = null,
        evidenceNoteThInput: String? = null
    ) {
        controller.updatePaymentDraft(
            paymentDirection = paymentDirection,
            amountMajorInput = amountMajorInput,
            evidenceNoteThInput = evidenceNoteThInput
        )
    }

    fun recordPaymentWithJournal() {
        viewModelScope.launch {
            controller.recordPaymentWithJournal()
        }
    }

    fun updateAllocationDraft(
        paymentId: String? = null,
        targetType: String? = null,
        targetId: String? = null,
        allocationAmountMajorInput: String? = null
    ) {
        controller.updateAllocationDraft(
            paymentId = paymentId,
            targetType = targetType,
            targetId = targetId,
            allocationAmountMajorInput = allocationAmountMajorInput
        )
    }

    fun fillMaxRemainingAllocationForSelection() {
        controller.fillMaxRemainingAllocationForSelection()
    }

    fun allocatePaymentBounded() {
        viewModelScope.launch {
            controller.allocatePaymentBounded()
        }
    }

    fun updateCashReconciliationInputs(
        openingCashMajorInput: String? = null,
        countedPhysicalCashMajorInput: String? = null,
        minimumRequiredCashMajorInput: String? = null
    ) {
        controller.updateCashReconciliationInputs(
            openingCashMajorInput = openingCashMajorInput,
            countedPhysicalCashMajorInput = countedPhysicalCashMajorInput,
            minimumRequiredCashMajorInput = minimumRequiredCashMajorInput
        )
    }

    fun recalculateCashReconciliation() {
        viewModelScope.launch {
            controller.recalculateCashReconciliation()
        }
    }

    companion object {
        fun factory(
            bootstrapRepository: OfflineBootstrapRepository,
            ledgerRepository: LedgerAndReconciliationRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val controller = LedgerAndReconciliationWorkflowController(
                    bootstrapRepository = bootstrapRepository,
                    ledgerRepository = ledgerRepository
                )
                return LedgerAndReconciliationViewModel(controller) as T
            }
        }
    }
}
