package com.oneproject.offline.ui.bills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.oneproject.offline.core.money.ExactMoneyMath
import com.oneproject.offline.data.local.dao.AtomicBillEditRequest
import com.oneproject.offline.data.local.dao.AtomicCommitResult
import com.oneproject.offline.data.local.dao.ClosedRoundAdjustmentResult
import com.oneproject.offline.data.local.dao.PersistedLineCommitInput
import com.oneproject.offline.data.local.dao.PolicyUpdateResult
import com.oneproject.offline.data.local.dao.RoundTransitionResult
import com.oneproject.offline.data.local.entity.ApprovalRecordEntity
import com.oneproject.offline.data.local.entity.BetSide
import com.oneproject.offline.data.local.entity.BillEntity
import com.oneproject.offline.data.local.entity.BillNumberSideLineEntity
import com.oneproject.offline.data.local.entity.BillRevisionEntity
import com.oneproject.offline.data.local.entity.NumberPolicyOverrideEntity
import com.oneproject.offline.data.local.entity.PolicyVersionEntity
import com.oneproject.offline.data.local.entity.RoundStatus
import com.oneproject.offline.data.repository.AuthoritativeOrderRepository
import com.oneproject.offline.data.repository.OfflineBootstrapRepository
import com.oneproject.offline.domain.engine.DomainOrderLineInput
import com.oneproject.offline.domain.engine.DomainPolicyDefaults
import com.oneproject.offline.domain.engine.NumberExpansionEngine
import com.oneproject.offline.domain.engine.PasteBatchParseResult
import com.oneproject.offline.domain.engine.PasteBatchParserAndQuoteHasher
import com.oneproject.offline.domain.engine.PolicyAndCapEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class BillsAndPolicyUiState(
    val activeRoundId: String = "round-demo-initial-001",
    val activeRoundCode: String = "DEMO-2026-001",
    val activeRoundStatus: RoundStatus = RoundStatus.OPEN,
    val activePolicyVersion: Long = 1L,
    val selectedSectionTab: Int = 0, // 0 = บิล/แก้ไข/ยกเลิก, 1 = ตั้งค่ามาตรฐาน/รายเลข/ประวัติ, 2 = จัดการสถานะงวด/ปรับปรุงงวดปิดแล้ว

    // ส่วนที่ 1: ค้นหาบิล รายละเอียด ประวัติ Revision การแก้ไข และการยกเลิกพร้อมเหตุผล
    val billSearchQuery: String = "",
    val matchingBills: List<BillEntity> = emptyList(),
    val selectedBill: BillEntity? = null,
    val selectedBillRevisions: List<BillRevisionEntity> = emptyList(),
    val selectedBillActiveLines: List<BillNumberSideLineEntity> = emptyList(),
    val editBillReplacementPasteText: String = "",
    val editBillReasonTh: String = "",
    val cancelBillReasonTh: String = "",

    // ส่วนที่ 2: ตั้งค่ามาตรฐาน / รายเลข / ประวัติ (แยก Inheritance รายช่อง แยก Apply-All กับ เปิด/ปิดรับ)
    val currentDefaultPolicy: PolicyVersionEntity? = null,
    val policyHistory: List<PolicyVersionEntity> = emptyList(),
    val currentOverrides: List<NumberPolicyOverrideEntity> = emptyList(),

    val defaultTopRateInput: String = "60",
    val defaultBottomRateInput: String = "60",
    val defaultReceiveCapThbInput: String = "1000",
    val defaultPayoutCapThbInput: String = "60000",
    val applyAllClearRateAndCapOverrides: Boolean = false,
    val resetOpenCloseOverrides: Boolean = false,
    val defaultPolicyChangeReasonInput: String = "",

    // ฟอร์มตั้งค่ารายเลข/ด้าน (Per-Field Explicit Override: null = สืบทอดจากค่ามาตรฐาน)
    val overrideNumberInput: String = "31",
    val overrideSide: BetSide = BetSide.TOP,
    val useExplicitRateOverride: Boolean = false,
    val overrideRateInput: String = "50",
    val useExplicitReceiveCapOverride: Boolean = false,
    val overrideReceiveCapThbInput: String = "500",
    val useExplicitPayoutCapOverride: Boolean = false,
    val overridePayoutCapThbInput: String = "30000",
    val useExplicitClosedOverride: Boolean = false,
    val overrideIsClosedForOrder: Boolean = false,
    val overrideReasonInput: String = "",

    // ส่วนที่ 3: จัดการสถานะงวด (Draft / Open / Paused / Closed) และการปรับปรุงงวดปิดแล้ว (D08)
    val roundTransitionReasonInput: String = "",
    val closedAdjustmentTargetBillIdInput: String = "",
    val closedAdjustmentApproverActorIdInput: String = "APPROVER-LOCAL-01",
    val closedAdjustmentReasonInput: String = "",
    val roundApprovals: List<ApprovalRecordEntity> = emptyList(),

    val statusBannerTh: String? = null,
    val errorBannerTh: String? = null
)

/**
 * ตัวควบคุมหลักสำหรับ Phase 05:
 * - ค้นหาบิล / ดูรายละเอียด / แก้ไขสร้าง Revision (ไม่นับยอดเก่าซ้ำ) / ยกเลิกพร้อมเหตุผล
 * - ตั้งค่านโยบายมาตรฐาน / ตั้งค่ารายเลขแบบแยก Inheritance รายช่อง / แยก Apply-All กับ เปิด/ปิดรับ / ประวัตินโยบาย
 * - จัดการสถานะงวด (Draft / Open / Paused / Closed) และการบันทึก Approved Adjustment สำหรับงวดที่ปิดแล้ว
 */
class BillsAndPolicyController(
    private val orderRepository: AuthoritativeOrderRepository,
    private val clockEpochMsProvider: () -> Long = { System.currentTimeMillis() },
    private val uuidProvider: () -> String = { UUID.randomUUID().toString() }
) {
    private val _state = MutableStateFlow(BillsAndPolicyUiState())
    val state: StateFlow<BillsAndPolicyUiState> = _state.asStateFlow()

    suspend fun loadRoundAndRefreshAll(roundId: String) {
        val round = orderRepository.getRoundSnapshot(roundId) ?: return
        val policy = orderRepository.getPolicyVersionSnapshot(round.roundId, round.activePolicyVersion)
        val history = orderRepository.getPolicyHistoryForRound(round.roundId)
        val overrides = orderRepository.getOverridesForPolicyVersion(round.roundId, round.activePolicyVersion)
        val bills = orderRepository.searchBillsInRound(round.roundId, _state.value.billSearchQuery)
        val approvals = orderRepository.getApprovalsForRound(round.roundId)

        val currentSelectedBillId = _state.value.selectedBill?.billId
        val refreshedSelected = if (currentSelectedBillId != null) {
            orderRepository.getBillWithActiveLines(currentSelectedBillId)
        } else {
            bills.firstOrNull()?.let { orderRepository.getBillWithActiveLines(it.billId) }
        }

        _state.update { current ->
            current.copy(
                activeRoundId = round.roundId,
                activeRoundCode = round.roundCode,
                activeRoundStatus = RoundStatus.fromStorageValue(round.status),
                activePolicyVersion = round.activePolicyVersion,
                currentDefaultPolicy = policy,
                policyHistory = history,
                currentOverrides = overrides,
                matchingBills = bills,
                selectedBill = refreshedSelected?.bill,
                selectedBillRevisions = refreshedSelected?.revisions.orEmpty(),
                selectedBillActiveLines = refreshedSelected?.activeLines.orEmpty(),
                defaultTopRateInput = policy?.defaultTopRateExact ?: current.defaultTopRateInput,
                defaultBottomRateInput = policy?.defaultBottomRateExact ?: current.defaultBottomRateInput,
                defaultReceiveCapThbInput = policy?.let {
                    ExactMoneyMath.formatMinorToThbString(it.defaultReceiveCapMinor).replace(",", "")
                } ?: current.defaultReceiveCapThbInput,
                defaultPayoutCapThbInput = policy?.let {
                    ExactMoneyMath.formatMinorToThbString(it.defaultPayoutCapMinor).replace(",", "")
                } ?: current.defaultPayoutCapThbInput,
                roundApprovals = approvals
            )
        }
    }

    fun selectSectionTab(tabIndex: Int) {
        _state.update { it.copy(selectedSectionTab = tabIndex.coerceIn(0, 2), errorBannerTh = null) }
    }

    // =========================================================================
    // 1. ค้นหาบิล / รายละเอียด / แก้ไขสร้าง Revision (ไม่นับยอดเก่าซ้ำ) / ยกเลิกพร้อมเหตุผล
    // =========================================================================

    suspend fun searchBills(query: String) {
        val roundId = _state.value.activeRoundId
        val bills = orderRepository.searchBillsInRound(roundId, query)
        _state.update {
            it.copy(
                billSearchQuery = query,
                matchingBills = bills,
                errorBannerTh = null
            )
        }
    }

    suspend fun selectBillForInspection(billId: String) {
        val snapshot = orderRepository.getBillWithActiveLines(billId)
        if (snapshot == null) {
            _state.update { it.copy(errorBannerTh = "ไม่พบบิลรหัส $billId ในเครื่อง") }
            return
        }
        val prefilledEditLines = snapshot.activeLines.joinToString("\n") { line ->
            val sideTh = if (line.side == BetSide.TOP.name) "บน" else "ล่าง"
            val thb = ExactMoneyMath.formatMinorToThbString(line.amountMinor).replace(",", "")
            "${line.numberTwoDigit} $sideTh $thb"
        }
        _state.update {
            it.copy(
                selectedBill = snapshot.bill,
                selectedBillRevisions = snapshot.revisions,
                selectedBillActiveLines = snapshot.activeLines,
                editBillReplacementPasteText = prefilledEditLines,
                editBillReasonTh = "",
                cancelBillReasonTh = "",
                errorBannerTh = null
            )
        }
    }

    fun updateBillEditInputs(
        replacementPasteText: String = _state.value.editBillReplacementPasteText,
        editReasonTh: String = _state.value.editBillReasonTh
    ) {
        _state.update {
            it.copy(
                editBillReplacementPasteText = replacementPasteText,
                editBillReasonTh = editReasonTh,
                errorBannerTh = null
            )
        }
    }

    fun updateBillCancelReason(cancelReasonTh: String) {
        _state.update { it.copy(cancelBillReasonTh = cancelReasonTh, errorBannerTh = null) }
    }

    /**
     * แก้ไขบิลโดยสร้าง Revision ใหม่และหักยอด Revision เดิมที่เคย Active ออกจากตัวนับก่อนบวกยอดใหม่
     * (Edit total = otherActiveBills + replacement, NOT old + replacement)
     */
    suspend fun submitSelectedBillEditRevision(
        explicitOperationKey: String? = null,
        actorId: String = "ACTOR-LOCAL-OPERATOR"
    ): AtomicCommitResult {
        val snap = _state.value
        val targetBill = snap.selectedBill
            ?: return AtomicCommitResult.Rejected("NO_BILL_SELECTED", "กรุณาเลือกบิลที่ต้องการแก้ไขก่อน")

        if (snap.editBillReasonTh.isBlank()) {
            val rejected = AtomicCommitResult.Rejected("MISSING_REVISION_REASON", "กรุณาระบุเหตุผลในการแก้ไขบิล")
            _state.update { it.copy(errorBannerTh = rejected.messageTh) }
            return rejected
        }

        val parsed = PasteBatchParserAndQuoteHasher.parsePasteBatchStrict(snap.editBillReplacementPasteText)
        if (parsed is PasteBatchParseResult.BlockedBatch) {
            val firstError = parsed.errors.first()
            val msg = "รายการแก้ไขไม่ถูกต้องที่บรรทัด ${firstError.lineNumber}: ${firstError.reasonTh}"
            _state.update { it.copy(errorBannerTh = msg) }
            return AtomicCommitResult.Rejected("INVALID_EDIT_LINES", msg)
        }

        val acceptedLines = (parsed as PasteBatchParseResult.AcceptedBatch).lines
        val round = orderRepository.getRoundSnapshot(snap.activeRoundId)
            ?: return AtomicCommitResult.Rejected("ROUND_NOT_FOUND", "ไม่พบงวดปัจจุบัน")
        val policy = orderRepository.getPolicyVersionSnapshot(round.roundId, round.activePolicyVersion)
            ?: return AtomicCommitResult.Rejected("POLICY_NOT_FOUND", "ไม่พบรุ่นนโยบายปัจจุบัน")

        val keys = acceptedLines.map { it.numberTwoDigit to it.side }.toSet()
        val overrides = orderRepository.getOverridesForKeys(round.roundId, round.activePolicyVersion, keys)
        val defaults = DomainPolicyDefaults(
            policyVersion = policy.policyVersion,
            defaultTopRateExact = policy.defaultTopRateExact,
            defaultBottomRateExact = policy.defaultBottomRateExact,
            defaultReceiveCapMinor = policy.defaultReceiveCapMinor,
            defaultPayoutCapMinor = policy.defaultPayoutCapMinor
        )

        val commitInputs = acceptedLines.map { line ->
            val effective = PolicyAndCapEngine.resolveEffectiveNumberSidePolicy(
                numberTwoDigit = line.numberTwoDigit,
                side = line.side,
                defaultPolicy = defaults,
                explicitOverride = overrides[line.numberTwoDigit to line.side]
            )
            val payoutMinor = ExactMoneyMath.computeProvisionalLinePayoutMinor(
                amountMinor = line.amountMinor,
                exactRate = effective.effectiveRateBigDecimal
            )
            PersistedLineCommitInput(
                numberTwoDigit = line.numberTwoDigit,
                side = line.side.name,
                amountMinor = line.amountMinor,
                rateSnapshotExact = effective.effectiveRateExact,
                provisionalPayoutMinor = payoutMinor,
                roundingMode = "HALF_UP"
            )
        }

        val operationKey = explicitOperationKey ?: "op-edit-${uuidProvider()}"
        val payloadSha = "edit|${targetBill.billId}|${round.activePolicyVersion}|${snap.editBillReasonTh.trim()}|" +
            commitInputs.joinToString(";") { "${it.numberTwoDigit}:${it.side}:${it.amountMinor}:${it.rateSnapshotExact}" }

        val result = orderRepository.commitBillEditRevisionAtomically(
            AtomicBillEditRequest(
                operationKey = operationKey,
                payloadSha256 = payloadSha,
                billId = targetBill.billId,
                newRevisionId = "rev-edit-${uuidProvider()}",
                expectedPolicyVersion = round.activePolicyVersion,
                revisionReasonTh = snap.editBillReasonTh.trim(),
                actorId = actorId,
                replacementLines = commitInputs,
                nowEpochMs = clockEpochMsProvider()
            )
        )

        when (result) {
            is AtomicCommitResult.Committed -> {
                loadRoundAndRefreshAll(round.roundId)
                selectBillForInspection(targetBill.billId)
                _state.update {
                    it.copy(
                        statusBannerTh = "แก้ไขบิล ${result.humanBillCode} สำเร็จ (สร้าง Revision #${result.revisionNumber} โดยตัดยอด Revision เก่าออกแล้ว)",
                        errorBannerTh = null
                    )
                }
            }
            is AtomicCommitResult.PayloadConflict -> {
                _state.update { it.copy(errorBannerTh = result.messageTh) }
            }
            is AtomicCommitResult.Rejected -> {
                _state.update { it.copy(errorBannerTh = "แก้ไขบิลไม่สำเร็จ (${result.code}): ${result.messageTh}") }
            }
        }
        return result
    }

    /**
     * ยกเลิกบิลพร้อมเหตุผล (ห้ามลบข้อมูลจริง แต่ถอนยอดออกจาก Counter และเก็บประวัติ Revision เดิมไว้ครบ)
     */
    suspend fun submitSelectedBillCancel(
        explicitOperationKey: String? = null,
        actorId: String = "ACTOR-LOCAL-OPERATOR"
    ): AtomicCommitResult {
        val snap = _state.value
        val targetBill = snap.selectedBill
            ?: return AtomicCommitResult.Rejected("NO_BILL_SELECTED", "กรุณาเลือกบิลที่ต้องการยกเลิก")

        val opKey = explicitOperationKey ?: "op-cancel-${uuidProvider()}"
        val payloadSha = "cancel|${targetBill.billId}|${snap.cancelBillReasonTh.trim()}"

        val result = orderRepository.cancelBillAtomically(
            operationKey = opKey,
            payloadSha256 = payloadSha,
            billId = targetBill.billId,
            cancelReasonTh = snap.cancelBillReasonTh.trim(),
            actorId = actorId,
            nowEpochMs = clockEpochMsProvider()
        )

        when (result) {
            is AtomicCommitResult.Committed -> {
                loadRoundAndRefreshAll(snap.activeRoundId)
                selectBillForInspection(targetBill.billId)
                _state.update {
                    it.copy(
                        statusBannerTh = "ยกเลิกบิล ${result.humanBillCode} เรียบร้อยแล้ว (ถอนยอดออกจากวงเงินและคงประวัติ Revision ไว้)",
                        errorBannerTh = null
                    )
                }
            }
            is AtomicCommitResult.PayloadConflict -> {
                _state.update { it.copy(errorBannerTh = result.messageTh) }
            }
            is AtomicCommitResult.Rejected -> {
                _state.update { it.copy(errorBannerTh = "ยกเลิกบิลไม่สำเร็จ (${result.code}): ${result.messageTh}") }
            }
        }
        return result
    }

    // =========================================================================
    // 2. ตั้งค่ามาตรฐาน / รายเลข / ประวัติ (แยก Inheritance รายช่อง และแยก Apply-All กับ เปิด/ปิดรับ)
    // =========================================================================

    fun updateDefaultPolicyForm(
        topRateInput: String = _state.value.defaultTopRateInput,
        bottomRateInput: String = _state.value.defaultBottomRateInput,
        receiveCapThbInput: String = _state.value.defaultReceiveCapThbInput,
        payoutCapThbInput: String = _state.value.defaultPayoutCapThbInput,
        applyAllClearRateAndCapOverrides: Boolean = _state.value.applyAllClearRateAndCapOverrides,
        resetOpenCloseOverrides: Boolean = _state.value.resetOpenCloseOverrides,
        reasonInput: String = _state.value.defaultPolicyChangeReasonInput
    ) {
        _state.update {
            it.copy(
                defaultTopRateInput = topRateInput,
                defaultBottomRateInput = bottomRateInput,
                defaultReceiveCapThbInput = receiveCapThbInput,
                defaultPayoutCapThbInput = payoutCapThbInput,
                applyAllClearRateAndCapOverrides = applyAllClearRateAndCapOverrides,
                resetOpenCloseOverrides = resetOpenCloseOverrides,
                defaultPolicyChangeReasonInput = reasonInput,
                errorBannerTh = null
            )
        }
    }

    suspend fun submitNewDefaultPolicyVersion(actorId: String = "ADMIN-LOCAL"): Boolean {
        val snap = _state.value
        val validTopRate = try {
            ExactMoneyMath.parseExactRate(snap.defaultTopRateInput).toPlainString()
        } catch (e: RuntimeException) {
            _state.update { it.copy(errorBannerTh = "อัตราจ่ายบนมาตรฐานไม่ถูกต้อง: ${e.message}") }
            return false
        }
        val validBottomRate = try {
            ExactMoneyMath.parseExactRate(snap.defaultBottomRateInput).toPlainString()
        } catch (e: RuntimeException) {
            _state.update { it.copy(errorBannerTh = "อัตราจ่ายล่างมาตรฐานไม่ถูกต้อง: ${e.message}") }
            return false
        }
        val receiveCapMinor = try {
            ExactMoneyMath.parseThbToMinorUnits(snap.defaultReceiveCapThbInput)
        } catch (e: RuntimeException) {
            _state.update { it.copy(errorBannerTh = "เพดานรับมาตรฐานไม่ถูกต้อง: ${e.message}") }
            return false
        }
        val payoutCapMinor = try {
            ExactMoneyMath.parseThbToMinorUnits(snap.defaultPayoutCapThbInput)
        } catch (e: RuntimeException) {
            _state.update { it.copy(errorBannerTh = "เพดานจ่ายมาตรฐานไม่ถูกต้อง: ${e.message}") }
            return false
        }

        val result = orderRepository.publishNewDefaultPolicyVersion(
            roundId = snap.activeRoundId,
            newTopRateExact = validTopRate,
            newBottomRateExact = validBottomRate,
            newReceiveCapMinor = receiveCapMinor,
            newPayoutCapMinor = payoutCapMinor,
            applyAllClearRateAndCapOverrides = snap.applyAllClearRateAndCapOverrides,
            resetOpenCloseOverrides = snap.resetOpenCloseOverrides,
            changeReasonTh = snap.defaultPolicyChangeReasonInput,
            actorId = actorId,
            nowEpochMs = clockEpochMsProvider()
        )

        return when (result) {
            is PolicyUpdateResult.Updated -> {
                loadRoundAndRefreshAll(snap.activeRoundId)
                _state.update {
                    it.copy(
                        defaultPolicyChangeReasonInput = "",
                        statusBannerTh = "บันทึกนโยบายมาตรฐานรุ่น #${result.newPolicyVersion} สำเร็จ (เก็บประวัติรุ่น #${result.previousPolicyVersion} ไว้ครบถ้วน)",
                        errorBannerTh = null
                    )
                }
                true
            }
            is PolicyUpdateResult.Rejected -> {
                _state.update { it.copy(errorBannerTh = result.reasonTh) }
                false
            }
        }
    }

    fun updateNumberOverrideForm(
        numberInput: String = _state.value.overrideNumberInput,
        side: BetSide = _state.value.overrideSide,
        useExplicitRate: Boolean = _state.value.useExplicitRateOverride,
        rateInput: String = _state.value.overrideRateInput,
        useExplicitReceiveCap: Boolean = _state.value.useExplicitReceiveCapOverride,
        receiveCapThbInput: String = _state.value.overrideReceiveCapThbInput,
        useExplicitPayoutCap: Boolean = _state.value.useExplicitPayoutCapOverride,
        payoutCapThbInput: String = _state.value.overridePayoutCapThbInput,
        useExplicitClosed: Boolean = _state.value.useExplicitClosedOverride,
        isClosedForOrder: Boolean = _state.value.overrideIsClosedForOrder,
        reasonInput: String = _state.value.overrideReasonInput
    ) {
        _state.update {
            it.copy(
                overrideNumberInput = numberInput,
                overrideSide = side,
                useExplicitRateOverride = useExplicitRate,
                overrideRateInput = rateInput,
                useExplicitReceiveCapOverride = useExplicitReceiveCap,
                overrideReceiveCapThbInput = receiveCapThbInput,
                useExplicitPayoutCapOverride = useExplicitPayoutCap,
                overridePayoutCapThbInput = payoutCapThbInput,
                useExplicitClosedOverride = useExplicitClosed,
                overrideIsClosedForOrder = isClosedForOrder,
                overrideReasonInput = reasonInput,
                errorBannerTh = null
            )
        }
    }

    suspend fun submitPerNumberOverride(actorId: String = "ADMIN-LOCAL"): Boolean {
        val snap = _state.value
        val validNumber = try {
            NumberExpansionEngine.validateTwoDigitNumber(snap.overrideNumberInput)
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(errorBannerTh = e.message) }
            return false
        }

        val explicitRate = if (snap.useExplicitRateOverride) {
            try {
                ExactMoneyMath.parseExactRate(snap.overrideRateInput).toPlainString()
            } catch (e: RuntimeException) {
                _state.update { it.copy(errorBannerTh = "อัตราจ่ายรายเลขไม่ถูกต้อง: ${e.message}") }
                return false
            }
        } else null

        val explicitReceiveCapMinor = if (snap.useExplicitReceiveCapOverride) {
            try {
                ExactMoneyMath.parseThbToMinorUnits(snap.overrideReceiveCapThbInput)
            } catch (e: RuntimeException) {
                _state.update { it.copy(errorBannerTh = "เพดานรับรายเลขไม่ถูกต้อง: ${e.message}") }
                return false
            }
        } else null

        val explicitPayoutCapMinor = if (snap.useExplicitPayoutCapOverride) {
            try {
                ExactMoneyMath.parseThbToMinorUnits(snap.overridePayoutCapThbInput)
            } catch (e: RuntimeException) {
                _state.update { it.copy(errorBannerTh = "เพดานจ่ายรายเลขไม่ถูกต้อง: ${e.message}") }
                return false
            }
        } else null

        val explicitClosed = if (snap.useExplicitClosedOverride) {
            snap.overrideIsClosedForOrder
        } else null

        val result = orderRepository.savePerNumberExplicitOverride(
            roundId = snap.activeRoundId,
            numberTwoDigit = validNumber,
            side = snap.overrideSide,
            overrideRateExact = explicitRate,
            overrideReceiveCapMinor = explicitReceiveCapMinor,
            overridePayoutCapMinor = explicitPayoutCapMinor,
            overrideIsClosed = explicitClosed,
            overrideReasonTh = snap.overrideReasonInput,
            actorId = actorId,
            nowEpochMs = clockEpochMsProvider()
        )

        return when (result) {
            is PolicyUpdateResult.Updated -> {
                loadRoundAndRefreshAll(snap.activeRoundId)
                _state.update {
                    it.copy(
                        overrideReasonInput = "",
                        statusBannerTh = "ตั้งค่ารายเลข $validNumber (${snap.overrideSide.thaiLabel}) สำเร็จ (รุ่นนโยบาย #${result.newPolicyVersion})",
                        errorBannerTh = null
                    )
                }
                true
            }
            is PolicyUpdateResult.Rejected -> {
                _state.update { it.copy(errorBannerTh = result.reasonTh) }
                false
            }
        }
    }

    // =========================================================================
    // 3. จัดการสถานะงวด (Draft / Open / Paused / Closed) และการปรับปรุงงวดที่ปิดแล้ว (D08)
    // =========================================================================

    fun updateRoundTransitionReason(reason: String) {
        _state.update { it.copy(roundTransitionReasonInput = reason, errorBannerTh = null) }
    }

    suspend fun transitionActiveRoundStatus(
        targetStatus: RoundStatus,
        actorId: String = "ADMIN-LOCAL"
    ): Boolean {
        val snap = _state.value
        val result = orderRepository.transitionRoundStatus(
            roundId = snap.activeRoundId,
            targetStatus = targetStatus,
            reasonTh = snap.roundTransitionReasonInput,
            actorId = actorId,
            nowEpochMs = clockEpochMsProvider()
        )
        return when (result) {
            is RoundTransitionResult.Transitioned -> {
                loadRoundAndRefreshAll(snap.activeRoundId)
                _state.update {
                    it.copy(
                        roundTransitionReasonInput = "",
                        statusBannerTh = "เปลี่ยนสถานะงวดเป็น ${result.newStatus.thaiLabel} [${result.newStatus.statusCodeText}] สำเร็จ",
                        errorBannerTh = null
                    )
                }
                true
            }
            is RoundTransitionResult.Rejected -> {
                _state.update { it.copy(errorBannerTh = result.reasonTh) }
                false
            }
        }
    }

    fun updateClosedRoundAdjustmentForm(
        targetBillId: String = _state.value.closedAdjustmentTargetBillIdInput,
        approverActorId: String = _state.value.closedAdjustmentApproverActorIdInput,
        reasonTh: String = _state.value.closedAdjustmentReasonInput
    ) {
        _state.update {
            it.copy(
                closedAdjustmentTargetBillIdInput = targetBillId,
                closedAdjustmentApproverActorIdInput = approverActorId,
                closedAdjustmentReasonInput = reasonTh,
                errorBannerTh = null
            )
        }
    }

    suspend fun submitClosedRoundApprovedAdjustment(): Boolean {
        val snap = _state.value
        val approvalId = "APR-CLOSED-${uuidProvider()}"
        val result = orderRepository.recordClosedRoundApprovedAdjustment(
            approvalId = approvalId,
            roundId = snap.activeRoundId,
            targetBillId = snap.closedAdjustmentTargetBillIdInput.ifBlank { "ROUND-LEVEL-ADJ" },
            approvedByActorId = snap.closedAdjustmentApproverActorIdInput.ifBlank { "APPROVER-LOCAL-01" },
            adjustmentReasonTh = snap.closedAdjustmentReasonInput,
            nowEpochMs = clockEpochMsProvider()
        )
        return when (result) {
            is ClosedRoundAdjustmentResult.Approved -> {
                loadRoundAndRefreshAll(snap.activeRoundId)
                _state.update {
                    it.copy(
                        closedAdjustmentReasonInput = "",
                        statusBannerTh = "บันทึกรายการอนุมัติปรับปรุงงวดปิดแล้ว (#${result.approvalId}) สำเร็จ",
                        errorBannerTh = null
                    )
                }
                true
            }
            is ClosedRoundAdjustmentResult.Rejected -> {
                _state.update { it.copy(errorBannerTh = result.reasonTh) }
                false
            }
        }
    }
}

class BillsAndPolicyViewModel(
    private val bootstrapRepository: OfflineBootstrapRepository,
    orderRepository: AuthoritativeOrderRepository
) : ViewModel() {

    val controller = BillsAndPolicyController(orderRepository = orderRepository)
    val uiState: StateFlow<BillsAndPolicyUiState> = controller.state

    init {
        viewModelScope.launch {
            bootstrapRepository.observeLatestRound().collectLatest { round ->
                if (round != null) {
                    controller.loadRoundAndRefreshAll(round.roundId)
                }
            }
        }
    }

    fun selectSectionTab(index: Int) = controller.selectSectionTab(index)

    fun searchBills(query: String) {
        viewModelScope.launch { controller.searchBills(query) }
    }

    fun selectBill(billId: String) {
        viewModelScope.launch { controller.selectBillForInspection(billId) }
    }

    fun updateBillEditInputs(
        replacementPasteText: String = uiState.value.editBillReplacementPasteText,
        editReasonTh: String = uiState.value.editBillReasonTh
    ) = controller.updateBillEditInputs(replacementPasteText, editReasonTh)

    fun submitBillEditRevision() {
        viewModelScope.launch { controller.submitSelectedBillEditRevision() }
    }

    fun updateBillCancelReason(reason: String) = controller.updateBillCancelReason(reason)

    fun submitBillCancel() {
        viewModelScope.launch { controller.submitSelectedBillCancel() }
    }

    fun updateDefaultPolicyForm(
        topRateInput: String = uiState.value.defaultTopRateInput,
        bottomRateInput: String = uiState.value.defaultBottomRateInput,
        receiveCapThbInput: String = uiState.value.defaultReceiveCapThbInput,
        payoutCapThbInput: String = uiState.value.defaultPayoutCapThbInput,
        applyAllClearRateAndCapOverrides: Boolean = uiState.value.applyAllClearRateAndCapOverrides,
        resetOpenCloseOverrides: Boolean = uiState.value.resetOpenCloseOverrides,
        reasonInput: String = uiState.value.defaultPolicyChangeReasonInput
    ) = controller.updateDefaultPolicyForm(
        topRateInput,
        bottomRateInput,
        receiveCapThbInput,
        payoutCapThbInput,
        applyAllClearRateAndCapOverrides,
        resetOpenCloseOverrides,
        reasonInput
    )

    fun submitNewDefaultPolicy() {
        viewModelScope.launch { controller.submitNewDefaultPolicyVersion() }
    }

    fun updateNumberOverrideForm(
        numberInput: String = uiState.value.overrideNumberInput,
        side: BetSide = uiState.value.overrideSide,
        useExplicitRate: Boolean = uiState.value.useExplicitRateOverride,
        rateInput: String = uiState.value.overrideRateInput,
        useExplicitReceiveCap: Boolean = uiState.value.useExplicitReceiveCapOverride,
        receiveCapThbInput: String = uiState.value.overrideReceiveCapThbInput,
        useExplicitPayoutCap: Boolean = uiState.value.useExplicitPayoutCapOverride,
        payoutCapThbInput: String = uiState.value.overridePayoutCapThbInput,
        useExplicitClosed: Boolean = uiState.value.useExplicitClosedOverride,
        isClosedForOrder: Boolean = uiState.value.overrideIsClosedForOrder,
        reasonInput: String = uiState.value.overrideReasonInput
    ) = controller.updateNumberOverrideForm(
        numberInput,
        side,
        useExplicitRate,
        rateInput,
        useExplicitReceiveCap,
        receiveCapThbInput,
        useExplicitPayoutCap,
        payoutCapThbInput,
        useExplicitClosed,
        isClosedForOrder,
        reasonInput
    )

    fun submitPerNumberOverride() {
        viewModelScope.launch { controller.submitPerNumberOverride() }
    }

    fun updateRoundTransitionReason(reason: String) = controller.updateRoundTransitionReason(reason)

    fun transitionRoundStatus(targetStatus: RoundStatus) {
        viewModelScope.launch { controller.transitionActiveRoundStatus(targetStatus) }
    }

    fun updateClosedRoundAdjustmentForm(
        targetBillId: String = uiState.value.closedAdjustmentTargetBillIdInput,
        approverActorId: String = uiState.value.closedAdjustmentApproverActorIdInput,
        reasonTh: String = uiState.value.closedAdjustmentReasonInput
    ) = controller.updateClosedRoundAdjustmentForm(targetBillId, approverActorId, reasonTh)

    fun submitClosedRoundAdjustment() {
        viewModelScope.launch { controller.submitClosedRoundApprovedAdjustment() }
    }

    companion object {
        fun factory(
            bootstrapRepository: OfflineBootstrapRepository,
            orderRepository: AuthoritativeOrderRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return BillsAndPolicyViewModel(bootstrapRepository, orderRepository) as T
            }
        }
    }
}
