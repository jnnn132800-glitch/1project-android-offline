package com.oneproject.offline.ui.counterparty

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.oneproject.offline.core.money.ExactMoneyMath
import com.oneproject.offline.data.local.dao.ManualTransferBatchCreateResult
import com.oneproject.offline.data.local.dao.ManualTransferLineDraft
import com.oneproject.offline.data.local.dao.ManualTransferResponseResult
import com.oneproject.offline.data.local.entity.BetSide
import com.oneproject.offline.data.local.entity.TransferResponseStatus
import com.oneproject.offline.data.repository.CounterpartyTransferRoundSnapshot
import com.oneproject.offline.data.repository.LedgerAndReconciliationRepository
import com.oneproject.offline.data.repository.OfflineBootstrapRepository
import com.oneproject.offline.domain.engine.NumberExpansionEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class CounterpartyTransferUiState(
    val roundId: String = "",
    val roundCode: String = "",
    val selectedContractId: String = "",
    val draftNumberInput: String = "31",
    val draftSide: BetSide = BetSide.TOP,
    val draftAmountMajorInput: String = "50.00",
    val stagedDraftLines: List<ManualTransferLineDraft> = emptyList(),

    // ส่วนบันทึกผลตอบรับจากคู่สัญญาด้วยมือ
    val selectedBatchIdForResponse: String? = null,
    val selectedResponseStatus: TransferResponseStatus = TransferResponseStatus.ACCEPTED,
    val partialAcceptedMajorInput: String = "25.00",
    val responseReferenceNoteThInput: String = "",

    val roundSnapshot: CounterpartyTransferRoundSnapshot? = null,
    val statusBannerTh: String? = null,
    val errorBannerTh: String? = null
)

/**
 * Deterministic Controller สำหรับ Phase 10:
 * - จัดการคู่สัญญา สัญญาการรับความเสี่ยง และใบตีออกด้วยมือ (Manual Transfer Batches)
 * - บังคับให้ใบตีออกที่สร้างใหม่มีสถานะ PENDING เสมอ และลดความเสี่ยงได้ 0 สตางค์
 * - บันทึกผลตอบรับจากคู่สัญญาด้วยมือ (ACCEPTED / PARTIAL / REJECTED)
 * - แยกการตั้งหนี้คู่สัญญา (COUNTERPARTY_SETTLEMENT) ออกจากความเสี่ยง
 * - ปิดการเชื่อมต่อหรือโอนเงินออกภายนอกจริงทั้งหมด (isExternalExecutionDisabled = true)
 */
class CounterpartyTransferWorkflowController(
    private val bootstrapRepository: OfflineBootstrapRepository,
    private val ledgerRepository: LedgerAndReconciliationRepository,
    private val clockEpochMsProvider: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: (String) -> String = { prefix ->
        "$prefix-${UUID.randomUUID().toString().take(8).uppercase()}"
    }
) {
    private val _uiState = MutableStateFlow(CounterpartyTransferUiState())
    val uiState: StateFlow<CounterpartyTransferUiState> = _uiState.asStateFlow()

    suspend fun initializeAndRefresh() {
        val nowMs = clockEpochMsProvider()
        val status = bootstrapRepository.getCurrentShellStatus()
        val roundId = status.activeRound?.roundId ?: OfflineBootstrapRepository.DEFAULT_ROUND_ID
        val roundCode = status.activeRound?.roundCode ?: "DEMO-2026-001"
        val (_, contract) = ledgerRepository.ensureDemoCounterpartyAndContractSeeded(roundId, nowMs)
        val snap = ledgerRepository.getCounterpartyTransferRoundSnapshot(roundId, nowMs)

        _uiState.update { current ->
            val defaultPendingBatchId = current.selectedBatchIdForResponse
                ?.takeIf { id -> snap.batches.any { b -> b.batchId == id && b.status == TransferResponseStatus.PENDING.name } }
                ?: snap.batches.firstOrNull { it.status == TransferResponseStatus.PENDING.name }?.batchId

            current.copy(
                roundId = roundId,
                roundCode = roundCode,
                selectedContractId = current.selectedContractId.ifBlank { contract.contractId },
                selectedBatchIdForResponse = defaultPendingBatchId,
                roundSnapshot = snap
            )
        }
    }

    fun updateDraftLineInputs(
        numberInput: String? = null,
        side: BetSide? = null,
        amountMajorInput: String? = null
    ) {
        _uiState.update { current ->
            current.copy(
                draftNumberInput = numberInput ?: current.draftNumberInput,
                draftSide = side ?: current.draftSide,
                draftAmountMajorInput = amountMajorInput ?: current.draftAmountMajorInput,
                errorBannerTh = null
            )
        }
    }

    fun addDraftLineToStage() {
        val state = _uiState.value
        val validNum = try {
            NumberExpansionEngine.validateTwoDigitNumber(state.draftNumberInput)
        } catch (e: IllegalArgumentException) {
            _uiState.update { it.copy(errorBannerTh = "เลข 2 ตัวไม่ถูกต้อง: ${e.message}") }
            return
        }
        val amountMinor = try {
            ExactMoneyMath.parseMajorStringToMinor(state.draftAmountMajorInput)
        } catch (e: IllegalArgumentException) {
            _uiState.update { it.copy(errorBannerTh = "จำนวนเงินตีออกไม่ถูกต้อง: ${e.message}") }
            return
        }
        if (state.stagedDraftLines.any { it.numberTwoDigit == validNum && it.side == state.draftSide }) {
            _uiState.update {
                it.copy(errorBannerTh = "มีเลข $validNum (${state.draftSide.thaiLabel}) อยู่ในรายการร่างใบตีออกแล้ว")
            }
            return
        }

        val updated = state.stagedDraftLines + ManualTransferLineDraft(
            numberTwoDigit = validNum,
            side = state.draftSide,
            submittedAmountMinor = amountMinor
        )
        _uiState.update {
            it.copy(
                stagedDraftLines = updated,
                statusBannerTh = "เพิ่มเลข $validNum (${state.draftSide.thaiLabel}) ยอด ${ExactMoneyMath.formatMinorToThbString(amountMinor)} บาท ลงรายการร่างใบตีออกแล้ว",
                errorBannerTh = null
            )
        }
    }

    fun clearStagedDraftLines() {
        _uiState.update {
            it.copy(
                stagedDraftLines = emptyList(),
                errorBannerTh = null
            )
        }
    }

    suspend fun submitManualTransferBatchPending(
        actorId: String = "LOCAL_OPERATOR_01"
    ): ManualTransferBatchCreateResult {
        val state = _uiState.value
        val lines = if (state.stagedDraftLines.isNotEmpty()) {
            state.stagedDraftLines
        } else {
            val validNum = try {
                NumberExpansionEngine.validateTwoDigitNumber(state.draftNumberInput)
            } catch (e: IllegalArgumentException) {
                val reason = "เลข 2 ตัวไม่ถูกต้อง: ${e.message}"
                _uiState.update { it.copy(errorBannerTh = reason, statusBannerTh = null) }
                return ManualTransferBatchCreateResult.Rejected(reason)
            }
            val amountMinor = try {
                ExactMoneyMath.parseMajorStringToMinor(state.draftAmountMajorInput)
            } catch (e: IllegalArgumentException) {
                val reason = "จำนวนเงินตีออกไม่ถูกต้อง: ${e.message}"
                _uiState.update { it.copy(errorBannerTh = reason, statusBannerTh = null) }
                return ManualTransferBatchCreateResult.Rejected(reason)
            }
            listOf(ManualTransferLineDraft(validNum, state.draftSide, amountMinor))
        }

        val nowMs = clockEpochMsProvider()
        val batchId = idGenerator("MTB")
        val res = ledgerRepository.createManualTransferBatchPending(
            batchId = batchId,
            roundId = state.roundId,
            contractId = state.selectedContractId,
            draftLines = lines,
            actorId = actorId,
            nowEpochMs = nowMs
        )

        when (res) {
            is ManualTransferBatchCreateResult.CreatedPending -> {
                val snap = ledgerRepository.getCounterpartyTransferRoundSnapshot(state.roundId, nowMs)
                _uiState.update {
                    it.copy(
                        stagedDraftLines = emptyList(),
                        selectedBatchIdForResponse = res.batchId,
                        roundSnapshot = snap,
                        statusBannerTh = "สร้างใบตีออกด้วยมือ ${res.batchId} สำเร็จในสถานะ PENDING (ยอดส่ง ${ExactMoneyMath.formatMinorToThbString(res.totalSubmittedMinor)} บาท • ยังไม่ลดความเสี่ยงจนกว่าจะบันทึกผลตอบรับ)",
                        errorBannerTh = null
                    )
                }
            }
            is ManualTransferBatchCreateResult.Rejected -> {
                _uiState.update {
                    it.copy(
                        errorBannerTh = res.reasonTh,
                        statusBannerTh = null
                    )
                }
            }
        }
        return res
    }

    fun updateResponseDraft(
        batchId: String? = null,
        responseStatus: TransferResponseStatus? = null,
        partialAcceptedMajorInput: String? = null,
        referenceNoteThInput: String? = null
    ) {
        _uiState.update { current ->
            current.copy(
                selectedBatchIdForResponse = batchId ?: current.selectedBatchIdForResponse,
                selectedResponseStatus = responseStatus ?: current.selectedResponseStatus,
                partialAcceptedMajorInput = partialAcceptedMajorInput ?: current.partialAcceptedMajorInput,
                responseReferenceNoteThInput = referenceNoteThInput ?: current.responseReferenceNoteThInput,
                errorBannerTh = null
            )
        }
    }

    suspend fun recordManualTransferResponse(
        actorId: String = "LOCAL_OPERATOR_01"
    ): ManualTransferResponseResult {
        val state = _uiState.value
        val batchId = state.selectedBatchIdForResponse
        if (batchId.isNullOrBlank()) {
            val reason = "กรุณาเลือกใบตีออกที่อยู่ในสถานะ PENDING ก่อนบันทึกผลตอบรับ"
            _uiState.update { it.copy(errorBannerTh = reason, statusBannerTh = null) }
            return ManualTransferResponseResult.Rejected(reason)
        }

        val batchSnap = state.roundSnapshot?.batches?.find { it.batchId == batchId }
        val partialMap = mutableMapOf<Pair<String, BetSide>, Long>()
        if (state.selectedResponseStatus == TransferResponseStatus.PARTIAL) {
            val partialMinor = try {
                ExactMoneyMath.parseMajorStringToMinor(state.partialAcceptedMajorInput)
            } catch (e: IllegalArgumentException) {
                val reason = "ยอดรับบางส่วนไม่ถูกต้อง: ${e.message}"
                _uiState.update { it.copy(errorBannerTh = reason, statusBannerTh = null) }
                return ManualTransferResponseResult.Rejected(reason)
            }
            batchSnap?.lines?.forEach { line ->
                partialMap[line.numberTwoDigit to BetSide.valueOf(line.side)] = partialMinor
            }
        }

        val nowMs = clockEpochMsProvider()
        val respId = idGenerator("TRESP")
        val settlementTxId = idGenerator("LTX-CP")
        val settlementOpKey = idGenerator("OP-CP")

        val res = ledgerRepository.recordManualTransferResponse(
            responseId = respId,
            batchId = batchId,
            responseStatus = state.selectedResponseStatus,
            partialAcceptedMinorByKey = partialMap,
            manualReferenceNoteTh = state.responseReferenceNoteThInput,
            settlementLedgerTxId = settlementTxId,
            settlementOperationKey = settlementOpKey,
            actorId = actorId,
            nowEpochMs = nowMs
        )

        when (res) {
            is ManualTransferResponseResult.Recorded -> {
                val snap = ledgerRepository.getCounterpartyTransferRoundSnapshot(state.roundId, nowMs)
                val nextPendingId = snap.batches.firstOrNull { it.status == TransferResponseStatus.PENDING.name }?.batchId
                _uiState.update {
                    it.copy(
                        selectedBatchIdForResponse = nextPendingId,
                        responseReferenceNoteThInput = "",
                        roundSnapshot = snap,
                        statusBannerTh = "บันทึกผลตอบรับใบตีออก ${res.batchId} สถานะ ${res.finalBatchStatus.name} สำเร็จ (ยอดรับจริง ${ExactMoneyMath.formatMinorToThbString(res.totalAcceptedMinor)} บาท • สิทธิรับคืนตามสัญญาสูงสุด ${ExactMoneyMath.formatMinorToThbString(res.totalAcceptedRecoverablePayoutMinor)} บาท)",
                        errorBannerTh = null
                    )
                }
            }
            is ManualTransferResponseResult.Rejected -> {
                _uiState.update {
                    it.copy(
                        errorBannerTh = res.reasonTh,
                        statusBannerTh = null
                    )
                }
            }
        }
        return res
    }
}

class CounterpartyTransferViewModel(
    private val controller: CounterpartyTransferWorkflowController
) : ViewModel() {

    val uiState: StateFlow<CounterpartyTransferUiState> = controller.uiState

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

    fun updateDraftLineInputs(
        numberInput: String? = null,
        side: BetSide? = null,
        amountMajorInput: String? = null
    ) {
        controller.updateDraftLineInputs(
            numberInput = numberInput,
            side = side,
            amountMajorInput = amountMajorInput
        )
    }

    fun addDraftLineToStage() {
        controller.addDraftLineToStage()
    }

    fun clearStagedDraftLines() {
        controller.clearStagedDraftLines()
    }

    fun submitManualTransferBatchPending() {
        viewModelScope.launch {
            controller.submitManualTransferBatchPending()
        }
    }

    fun updateResponseDraft(
        batchId: String? = null,
        responseStatus: TransferResponseStatus? = null,
        partialAcceptedMajorInput: String? = null,
        referenceNoteThInput: String? = null
    ) {
        controller.updateResponseDraft(
            batchId = batchId,
            responseStatus = responseStatus,
            partialAcceptedMajorInput = partialAcceptedMajorInput,
            referenceNoteThInput = referenceNoteThInput
        )
    }

    fun recordManualTransferResponse() {
        viewModelScope.launch {
            controller.recordManualTransferResponse()
        }
    }

    companion object {
        fun factory(
            bootstrapRepository: OfflineBootstrapRepository,
            ledgerRepository: LedgerAndReconciliationRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val controller = CounterpartyTransferWorkflowController(
                    bootstrapRepository = bootstrapRepository,
                    ledgerRepository = ledgerRepository
                )
                return CounterpartyTransferViewModel(controller) as T
            }
        }
    }
}
