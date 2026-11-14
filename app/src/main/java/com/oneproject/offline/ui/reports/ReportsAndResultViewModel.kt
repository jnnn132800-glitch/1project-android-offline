package com.oneproject.offline.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.oneproject.offline.data.local.dao.ApproveResultRevisionRequest
import com.oneproject.offline.data.local.dao.ResultApprovalOutcome
import com.oneproject.offline.data.local.dao.ResultEntitlementPreviewOutcome
import com.oneproject.offline.data.local.dao.ResultEntitlementPreviewSummary
import com.oneproject.offline.data.local.entity.BetSide
import com.oneproject.offline.data.local.entity.DurableOperationStatus
import com.oneproject.offline.data.local.entity.RoundStatus
import com.oneproject.offline.data.repository.AuthoritativeOrderRepository
import com.oneproject.offline.data.repository.BillEntitlementDrilldown
import com.oneproject.offline.data.repository.NumberSideReportDrilldown
import com.oneproject.offline.data.repository.OfflineBootstrapRepository
import com.oneproject.offline.data.repository.RoundReportAndDrilldownSnapshot
import com.oneproject.offline.domain.engine.PasteBatchParserAndQuoteHasher
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ReportsSubTab(val titleTh: String) {
    RESULT_APPROVAL("1. ประกาศผลและอนุมัติ"),
    ROUND_AND_BILL_DRILLDOWN("2. สรุปงวดและเจาะลึกรายบิล"),
    NUMBER_SIDE_DRILLDOWN("3. เจาะลึกรายเลข 00–99")
}

data class ReportsAndResultUiState(
    val roundId: String = "",
    val roundCode: String = "กำลังโหลด...",
    val roundStatus: RoundStatus = RoundStatus.OPEN,
    val selectedTab: ReportsSubTab = ReportsSubTab.RESULT_APPROVAL,
    val winningTopInput: String = "",
    val winningBottomInput: String = "",
    val approvalReasonInput: String = "",
    val previewSummary: ResultEntitlementPreviewSummary? = null,
    val pendingApprovalOperationKey: String? = null,
    val reportSnapshot: RoundReportAndDrilldownSnapshot? = null,
    val onlyWinningBillsFilter: Boolean = false,
    val selectedBillDrilldown: BillEntitlementDrilldown? = null,
    val selectedNumberSideDrilldown: NumberSideReportDrilldown? = null,
    val statusBannerTh: String? = null,
    val errorBannerTh: String? = null
)

/**
 * Pure Kotlin Controller สำหรับระบบประกาศผลรางวัล คำนวณสิทธิได้รับรางวัลแบบ Idempotent และรายงานเจาะลึก (Phase 07)
 *
 * กฎข้อ 01, 04_SPEC และ 05_UI:
 * 1. ต้องกดคำนวณตัวอย่างผลรางวัล (Preview) ก่อนอนุมัติ (Approve) เสมอ หากเปลี่ยนเลขบน/ล่างต้องคำนวณ Preview ใหม่
 * 2. การอนุมัติใช้ durable operation_key และรองรับ Idempotency ทั้งกรณีส่ง operation_key ซ้ำ และกรณีส่งเลขรางวัลชุดเดิมซ้ำ
 * 3. หากแก้ไขผลรางวัลจะเปลี่ยนรุ่นเดิมเป็น SUPERSEDED (เก็บประวัติครบถ้วน) และคำนวณ WinningEntitlement รุ่นใหม่
 * 4. สิทธิได้รับรางวัล (Winning Entitlement) ไม่ถือว่าจ่ายเงินสดจริง (winningentitlement != paid)
 */
class ReportsAndResultWorkflowController(
    private val bootstrapRepository: OfflineBootstrapRepository,
    private val orderRepository: AuthoritativeOrderRepository,
    private val scope: CoroutineScope,
    private val clockEpochMsProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val _uiState = MutableStateFlow(ReportsAndResultUiState())
    val uiState: StateFlow<ReportsAndResultUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
            bootstrapRepository.observeLatestRound().collect { round ->
                if (round != null) {
                    val status = RoundStatus.fromStorageValue(round.status)
                    _uiState.update {
                        it.copy(
                            roundId = round.roundId,
                            roundCode = round.roundCode,
                            roundStatus = status
                        )
                    }
                    refreshRoundReportSnapshot(round.roundId)
                }
            }
        }
    }

    fun selectSubTab(tab: ReportsSubTab) {
        _uiState.update {
            it.copy(
                selectedTab = tab,
                errorBannerTh = null
            )
        }
    }

    fun updateWinningTopInput(input: String) {
        val cleaned = input.filter { it.isDigit() }.take(2)
        _uiState.update { current ->
            val keepPreview = current.previewSummary?.winningTopTwoDigit == cleaned &&
                current.previewSummary.winningBottomTwoDigit == current.winningBottomInput
            current.copy(
                winningTopInput = cleaned,
                previewSummary = if (keepPreview) current.previewSummary else null,
                pendingApprovalOperationKey = if (keepPreview) current.pendingApprovalOperationKey else null,
                errorBannerTh = null
            )
        }
    }

    fun updateWinningBottomInput(input: String) {
        val cleaned = input.filter { it.isDigit() }.take(2)
        _uiState.update { current ->
            val keepPreview = current.previewSummary?.winningBottomTwoDigit == cleaned &&
                current.previewSummary.winningTopTwoDigit == current.winningTopInput
            current.copy(
                winningBottomInput = cleaned,
                previewSummary = if (keepPreview) current.previewSummary else null,
                pendingApprovalOperationKey = if (keepPreview) current.pendingApprovalOperationKey else null,
                errorBannerTh = null
            )
        }
    }

    fun updateApprovalReasonInput(input: String) {
        _uiState.update {
            it.copy(
                approvalReasonInput = input,
                errorBannerTh = null
            )
        }
    }

    fun setOnlyWinningBillsFilter(onlyWinning: Boolean) {
        _uiState.update { it.copy(onlyWinningBillsFilter = onlyWinning) }
    }

    fun clearBanners() {
        _uiState.update {
            it.copy(
                statusBannerTh = null,
                errorBannerTh = null
            )
        }
    }

    suspend fun refreshRoundReportSnapshot(targetRoundId: String? = null) {
        val rId = targetRoundId ?: _uiState.value.roundId
        if (rId.isBlank()) return
        val snapshot = orderRepository.getRoundReportAndDrilldownSnapshot(rId)
        val currentSelectedBillId = _uiState.value.selectedBillDrilldown?.bill?.billId
        val refreshedBillDrilldown = if (currentSelectedBillId != null) {
            orderRepository.getBillEntitlementDrilldown(rId, currentSelectedBillId)
        } else {
            null
        }
        val currentSelectedNumSide = _uiState.value.selectedNumberSideDrilldown
        val refreshedNumSideDrilldown = if (currentSelectedNumSide != null) {
            orderRepository.getNumberSideDrilldown(
                roundId = rId,
                numberTwoDigit = currentSelectedNumSide.numberTwoDigit,
                side = currentSelectedNumSide.side
            )
        } else {
            null
        }

        _uiState.update { current ->
            val prefillTop = if (current.winningTopInput.isBlank() && snapshot.activeResultRevision != null) {
                snapshot.activeResultRevision.winningTopTwoDigit
            } else {
                current.winningTopInput
            }
            val prefillBottom = if (current.winningBottomInput.isBlank() && snapshot.activeResultRevision != null) {
                snapshot.activeResultRevision.winningBottomTwoDigit
            } else {
                current.winningBottomInput
            }
            current.copy(
                reportSnapshot = snapshot,
                selectedBillDrilldown = refreshedBillDrilldown,
                selectedNumberSideDrilldown = refreshedNumSideDrilldown,
                winningTopInput = prefillTop,
                winningBottomInput = prefillBottom
            )
        }
    }

    /**
     * ขั้นตอนที่ 1: คำนวณตัวอย่างผลรางวัลและสิทธิได้รับรางวัล (Preview Entitlement)
     */
    suspend fun previewResultEntitlement(): ResultEntitlementPreviewOutcome {
        val current = _uiState.value
        if (current.roundId.isBlank()) {
            val msg = "ยังไม่ได้เลือกงวดสำหรับคำนวณผลรางวัล"
            _uiState.update { it.copy(errorBannerTh = msg, statusBannerTh = null) }
            return ResultEntitlementPreviewOutcome.Rejected("NO_ROUND", msg)
        }

        val outcome = orderRepository.previewResultEntitlements(
            roundId = current.roundId,
            winningTopTwoDigit = current.winningTopInput.trim(),
            winningBottomTwoDigit = current.winningBottomInput.trim()
        )

        when (outcome) {
            is ResultEntitlementPreviewOutcome.ValidPreview -> {
                val opKey = "op-result-${current.roundId}-${outcome.summary.winningTopTwoDigit}-${outcome.summary.winningBottomTwoDigit}-${UUID.randomUUID().toString().take(8)}"
                _uiState.update {
                    it.copy(
                        previewSummary = outcome.summary,
                        pendingApprovalOperationKey = opKey,
                        statusBannerTh = "คำนวณตัวอย่างผลรางวัลสำเร็จ (บน=${outcome.summary.winningTopTwoDigit}, ล่าง=${outcome.summary.winningBottomTwoDigit}) • พบรายการถูกรางวัล ${outcome.summary.winningLineCount} บรรทัด จาก ${outcome.summary.winningBillCount} บิล (ยังไม่ถือว่าจ่ายเงินสดจริง)",
                        errorBannerTh = null
                    )
                }
            }

            is ResultEntitlementPreviewOutcome.Rejected -> {
                _uiState.update {
                    it.copy(
                        previewSummary = null,
                        pendingApprovalOperationKey = null,
                        errorBannerTh = "[${outcome.code}] ${outcome.messageTh}",
                        statusBannerTh = null
                    )
                }
            }
        }
        return outcome
    }

    /**
     * ขั้นตอนที่ 2: ยืนยันอนุมัติผลรางวัลลงฐานข้อมูล Room แบบ Atomic และ Idempotent
     */
    suspend fun approvePreviewedResultRevision(
        explicitOperationKey: String? = null,
        actorId: String = "LOCAL_ADMIN"
    ): ResultApprovalOutcome {
        val current = _uiState.value
        val preview = current.previewSummary
        if (preview == null ||
            preview.winningTopTwoDigit != current.winningTopInput.trim() ||
            preview.winningBottomTwoDigit != current.winningBottomInput.trim()
        ) {
            val msg = "กรุณากด '1. คำนวณตัวอย่างผลรางวัล (Preview)' ก่อนกดอนุมัติผลรางวัลเสมอ"
            _uiState.update { it.copy(errorBannerTh = msg, statusBannerTh = null) }
            return ResultApprovalOutcome.Rejected("PREVIEW_REQUIRED_BEFORE_APPROVAL", msg)
        }

        val reason = current.approvalReasonInput.trim()
        if (reason.isBlank()) {
            val msg = "กรุณาระบุเหตุผลการอนุมัติหรือแก้ไขผลรางวัล"
            _uiState.update { it.copy(errorBannerTh = msg, statusBannerTh = null) }
            return ResultApprovalOutcome.Rejected("MISSING_APPROVAL_REASON", msg)
        }

        val opKey = explicitOperationKey
            ?: current.pendingApprovalOperationKey
            ?: "op-result-${current.roundId}-${preview.winningTopTwoDigit}-${preview.winningBottomTwoDigit}-${UUID.randomUUID().toString().take(8)}"

        val canonicalPayload = listOf(
            "round=${current.roundId}",
            "top=${preview.winningTopTwoDigit}",
            "bottom=${preview.winningBottomTwoDigit}",
            "reason=$reason"
        ).joinToString("|")
        val payloadSha256 = PasteBatchParserAndQuoteHasher.sha256Hex(canonicalPayload)

        val nowMs = clockEpochMsProvider()
        val request = ApproveResultRevisionRequest(
            operationKey = opKey,
            payloadSha256 = payloadSha256,
            roundId = current.roundId,
            newResultRevisionId = "resrev-${nowMs}-${UUID.randomUUID().toString().take(6)}",
            approvalId = "apv-res-${nowMs}-${UUID.randomUUID().toString().take(6)}",
            winningTopTwoDigit = preview.winningTopTwoDigit,
            winningBottomTwoDigit = preview.winningBottomTwoDigit,
            reasonTh = reason,
            actorId = actorId,
            nowEpochMs = nowMs
        )

        val outcome = orderRepository.approveResultRevisionAtomically(request)
        when (outcome) {
            is ResultApprovalOutcome.Approved -> {
                refreshRoundReportSnapshot(current.roundId)
                val replayTag = when {
                    outcome.replayedFromDurableOperation -> " [กู้คืนจาก operation_key เดิมโดยไม่สร้างซ้ำ]"
                    outcome.replayedFromIdenticalActiveResult -> " [ผลรางวัลตรงกับรุ่นที่อนุมัติอยู่แล้ว • คืนค่าเดิมแบบ Idempotent]"
                    else -> ""
                }
                _uiState.update {
                    it.copy(
                        pendingApprovalOperationKey = opKey,
                        statusBannerTh = "อนุมัติผลรางวัลรุ่นที่ #${outcome.revisionNumber} สำเร็จ (บน=${outcome.winningTopTwoDigit}, ล่าง=${outcome.winningBottomTwoDigit}) • สิทธิได้รับรางวัล ${outcome.winningEntitlementCount} รายการ (ยังไม่ถือว่าจ่ายเงินสดจริง)$replayTag",
                        errorBannerTh = null
                    )
                }
            }

            is ResultApprovalOutcome.PayloadConflict -> {
                _uiState.update {
                    it.copy(
                        errorBannerTh = "[PAYLOAD_CONFLICT] ${outcome.messageTh}",
                        statusBannerTh = null
                    )
                }
            }

            is ResultApprovalOutcome.Rejected -> {
                _uiState.update {
                    it.copy(
                        errorBannerTh = "[${outcome.code}] ${outcome.messageTh}",
                        statusBannerTh = null
                    )
                }
            }
        }
        return outcome
    }

    /**
     * กู้คืนสถานะการอนุมัติผลรางวัลหลังแอปถูกปิดหรือ Process Death ด้วย operation_key เดิม
     */
    suspend fun recoverPendingResultApprovalIfAny(operationKey: String): Boolean {
        val op = orderRepository.findCompletedOrRejectedOperation(operationKey) ?: return false
        if (op.status == DurableOperationStatus.COMPLETED.name && op.resultRefId != null) {
            refreshRoundReportSnapshot(_uiState.value.roundId)
            _uiState.update {
                it.copy(
                    statusBannerTh = "กู้คืนสถานะอนุมัติผลรางวัลสำเร็จจาก operation_key=$operationKey (อ้างอิง ${op.resultRefId})",
                    errorBannerTh = null
                )
            }
            return true
        }
        return false
    }

    /**
     * เลือกเจาะลึกรายบิลและบรรทัดในบิล (Bill & Line Entitlement Drilldown)
     */
    suspend fun selectBillForDrilldown(billId: String?) {
        val current = _uiState.value
        if (billId == null || current.roundId.isBlank()) {
            _uiState.update { it.copy(selectedBillDrilldown = null) }
            return
        }
        val drilldown = orderRepository.getBillEntitlementDrilldown(current.roundId, billId)
        _uiState.update { it.copy(selectedBillDrilldown = drilldown) }
    }

    /**
     * เลือกเจาะลึกรายเลข 00–99 และฝั่ง บน/ล่าง (Number-Side Drilldown)
     */
    suspend fun selectNumberSideForDrilldown(numberTwoDigit: String?, side: BetSide?) {
        val current = _uiState.value
        if (numberTwoDigit == null || side == null || current.roundId.isBlank()) {
            _uiState.update { it.copy(selectedNumberSideDrilldown = null) }
            return
        }
        val drilldown = orderRepository.getNumberSideDrilldown(
            roundId = current.roundId,
            numberTwoDigit = numberTwoDigit,
            side = side
        )
        _uiState.update { it.copy(selectedNumberSideDrilldown = drilldown) }
    }
}

class ReportsAndResultViewModel(
    bootstrapRepository: OfflineBootstrapRepository,
    orderRepository: AuthoritativeOrderRepository
) : ViewModel() {

    val controller = ReportsAndResultWorkflowController(
        bootstrapRepository = bootstrapRepository,
        orderRepository = orderRepository,
        scope = viewModelScope
    )

    val uiState: StateFlow<ReportsAndResultUiState> = controller.uiState

    fun selectSubTab(tab: ReportsSubTab) = controller.selectSubTab(tab)
    fun updateWinningTopInput(input: String) = controller.updateWinningTopInput(input)
    fun updateWinningBottomInput(input: String) = controller.updateWinningBottomInput(input)
    fun updateApprovalReasonInput(input: String) = controller.updateApprovalReasonInput(input)
    fun setOnlyWinningBillsFilter(onlyWinning: Boolean) = controller.setOnlyWinningBillsFilter(onlyWinning)
    fun clearBanners() = controller.clearBanners()

    fun previewResultEntitlementAsync() {
        viewModelScope.launch {
            controller.previewResultEntitlement()
        }
    }

    fun approvePreviewedResultRevisionAsync() {
        viewModelScope.launch {
            controller.approvePreviewedResultRevision()
        }
    }

    fun selectBillForDrilldownAsync(billId: String?) {
        viewModelScope.launch {
            controller.selectBillForDrilldown(billId)
        }
    }

    fun selectNumberSideForDrilldownAsync(numberTwoDigit: String?, side: BetSide?) {
        viewModelScope.launch {
            controller.selectNumberSideForDrilldown(numberTwoDigit, side)
        }
    }

    companion object {
        fun factory(
            bootstrapRepository: OfflineBootstrapRepository,
            orderRepository: AuthoritativeOrderRepository
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ReportsAndResultViewModel(
                        bootstrapRepository = bootstrapRepository,
                        orderRepository = orderRepository
                    ) as T
                }
            }
        }
    }
}
