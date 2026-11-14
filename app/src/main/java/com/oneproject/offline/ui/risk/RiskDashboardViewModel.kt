package com.oneproject.offline.ui.risk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.oneproject.offline.core.money.ExactMoneyMath
import com.oneproject.offline.data.repository.LedgerAndReconciliationRepository
import com.oneproject.offline.data.repository.OfflineBootstrapRepository
import com.oneproject.offline.data.repository.RoundRiskAndCashDashboardBundle
import com.oneproject.offline.domain.engine.CashScenarioPositionResult
import com.oneproject.offline.domain.engine.PolicyAndCapEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RiskDashboardUiState(
    val roundId: String = "",
    val roundCode: String = "",
    val openingCashMajorInput: String = "60000.00",
    val minimumRequiredCashMajorInput: String = "10000.00",
    val dashboardBundle: RoundRiskAndCashDashboardBundle? = null,
    val isSnapshotStale: Boolean = false,
    val isDecisionBlocked: Boolean = false,
    val cash01IndependentResult: CashScenarioPositionResult? = null,
    val statusBannerTh: String? = null,
    val errorBannerTh: String? = null
)

sealed interface StaleGuardedActionOutcome {
    data class Allowed(val asOfEpochMs: Long) : StaleGuardedActionOutcome
    data class BlockedByStaleRisk(val reasonTh: String) : StaleGuardedActionOutcome
    data class BlockedByCapBreach(val reasonTh: String) : StaleGuardedActionOutcome
}

/**
 * Deterministic Controller สำหรับ Phase 09:
 * - คำนวณความเสี่ยงขั้นต้น gross(outcome) และความเสี่ยงสุทธิ net(outcome) บนปริภูมิผลลัพธ์ 100 × 100 (D06)
 * - หักเฉพาะรายการตีออกที่คู่สัญญายอมรับแล้ว (ACCEPTED / PARTIAL) ที่ตรงกับผลลัพธ์นั้น
 * - คำนวณสถานการณ์เงินสด (Default Conservative vs Outcome-Matched Reference + CASH01 Fixture)
 * - ตรวจจับข้อมูลความเสี่ยงล้าสมัย (Stale Risk Detection) และบล็อกการตัดสินใจจนกว่าจะรีเฟรช
 */
class RiskDashboardWorkflowController(
    private val bootstrapRepository: OfflineBootstrapRepository,
    private val ledgerRepository: LedgerAndReconciliationRepository,
    private val clockEpochMsProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val _uiState = MutableStateFlow(RiskDashboardUiState())
    val uiState: StateFlow<RiskDashboardUiState> = _uiState.asStateFlow()

    suspend fun initializeAndRefresh() {
        val status = bootstrapRepository.getCurrentShellStatus()
        val roundId = status.activeRound?.roundId ?: OfflineBootstrapRepository.DEFAULT_ROUND_ID
        val roundCode = status.activeRound?.roundCode ?: "DEMO-2026-001"
        _uiState.update {
            it.copy(
                roundId = roundId,
                roundCode = roundCode
            )
        }
        recomputeRiskAndCashSnapshot()
    }

    fun updateCashInputs(
        openingCashMajorInput: String? = null,
        minimumRequiredCashMajorInput: String? = null
    ) {
        _uiState.update { current ->
            current.copy(
                openingCashMajorInput = openingCashMajorInput ?: current.openingCashMajorInput,
                minimumRequiredCashMajorInput = minimumRequiredCashMajorInput ?: current.minimumRequiredCashMajorInput,
                errorBannerTh = null
            )
        }
    }

    suspend fun recomputeRiskAndCashSnapshot() {
        val state = _uiState.value
        val roundId = state.roundId.ifBlank { OfflineBootstrapRepository.DEFAULT_ROUND_ID }
        val openingMinor = try {
            ExactMoneyMath.parseMajorStringToMinor(state.openingCashMajorInput)
        } catch (e: IllegalArgumentException) {
            _uiState.update { it.copy(errorBannerTh = "เงินสดต้นงวดไม่ถูกต้อง: ${e.message}") }
            return
        }
        val minRequiredMinor = try {
            ExactMoneyMath.parseMajorStringToMinor(state.minimumRequiredCashMajorInput)
        } catch (e: IllegalArgumentException) {
            _uiState.update { it.copy(errorBannerTh = "เกณฑ์เงินสดขั้นต่ำไม่ถูกต้อง: ${e.message}") }
            return
        }

        val nowMs = clockEpochMsProvider()
        val bundle = ledgerRepository.computeRoundRiskAndCashDashboardBundle(
            roundId = roundId,
            openingCashMinor = openingMinor,
            minimumRequiredCashMinor = minRequiredMinor,
            computedAsOfEpochMs = nowMs
        )

        // ประเมินชุดทดสอบอิสระ CASH01 ตาม 07_TEST_FIXTURES.json (opening=6000000, inflow=1000000, outflow=9000000, min=1000000)
        val cash01 = PolicyAndCapEngine.evaluateCashScenarioPosition(
            openingCashMinor = 6_000_000L,
            availableInflowsMinor = 1_000_000L,
            horizonDueOutflowsMinor = 9_000_000L,
            minimumRequiredCashMinor = 1_000_000L
        )

        _uiState.update {
            it.copy(
                roundId = roundId,
                roundCode = bundle.roundCode,
                dashboardBundle = bundle,
                isSnapshotStale = bundle.riskSnapshot.isStale,
                isDecisionBlocked = bundle.riskSnapshot.isDecisionBlockedByStaleRisk,
                cash01IndependentResult = cash01,
                statusBannerTh = "คำนวณความเสี่ยง 100×100 ผลลัพธ์สำเร็จ ณ เวลาในเครื่อง (as-of: ${bundle.riskSnapshot.computedAsOfEpochMs})",
                errorBannerTh = null
            )
        }
    }

    /**
     * ตรวจสอบว่ามีการเปลี่ยนแปลงบิล นโยบาย หรือผลตอบรับคู่สัญญาหลังจากเวลา as-of ล่าสุดหรือไม่
     * หากมี จะตั้งค่า isSnapshotStale = true และ isDecisionBlocked = true ทันที
     */
    suspend fun checkStalenessAgainstDatabase(): Boolean {
        val state = _uiState.value
        val bundle = state.dashboardBundle ?: return true
        val stale = ledgerRepository.isRiskSnapshotStale(
            roundId = state.roundId,
            snapshotComputedAsOfEpochMs = bundle.riskSnapshot.computedAsOfEpochMs
        )
        _uiState.update {
            it.copy(
                isSnapshotStale = stale,
                isDecisionBlocked = stale,
                errorBannerTh = if (stale) {
                    "บล็อกการตัดสินใจ: ข้อมูลความเสี่ยงล้าสมัย (มีการเปลี่ยนแปลงบิล/นโยบาย/คู่สัญญาหลังเวลา as-of ${bundle.riskSnapshot.computedAsOfEpochMs}) กรุณากดคำนวณความเสี่ยงใหม่"
                } else {
                    it.errorBannerTh
                }
            )
        }
        return stale
    }

    /**
     * กั้นการอนุมัติหรือตัดสินใจด้านวงเงินเมื่อข้อมูลความเสี่ยงยังล้าสมัย (Stale Risk Blocking)
     */
    suspend fun attemptDecisionGuardedByFreshRisk(
        blockOnCapBreach: Boolean = false
    ): StaleGuardedActionOutcome {
        val isStale = checkStalenessAgainstDatabase()
        val bundle = _uiState.value.dashboardBundle
        if (bundle == null || isStale) {
            val reason = "ไม่สามารถดำเนินการได้เนื่องจากข้อมูลความเสี่ยงไม่เป็นปัจจุบัน (Stale Risk Snapshot) กรุณารีเฟรชการคำนวณความเสี่ยงก่อน"
            _uiState.update { it.copy(errorBannerTh = reason, statusBannerTh = null) }
            return StaleGuardedActionOutcome.BlockedByStaleRisk(reason)
        }
        if (blockOnCapBreach && bundle.riskSnapshot.capBreachCellCount > 0) {
            val reason = "ไม่สามารถดำเนินการได้เนื่องจากพบเลขที่มียอดจ่ายสุทธิเกินเพดานจ่าย (${bundle.riskSnapshot.capBreachCellCount} รายการ)"
            _uiState.update { it.copy(errorBannerTh = reason, statusBannerTh = null) }
            return StaleGuardedActionOutcome.BlockedByCapBreach(reason)
        }
        return StaleGuardedActionOutcome.Allowed(bundle.riskSnapshot.computedAsOfEpochMs)
    }
}

class RiskDashboardViewModel(
    private val controller: RiskDashboardWorkflowController
) : ViewModel() {

    val uiState: StateFlow<RiskDashboardUiState> = controller.uiState

    init {
        viewModelScope.launch {
            controller.initializeAndRefresh()
        }
    }

    fun updateCashInputs(
        openingCashMajorInput: String? = null,
        minimumRequiredCashMajorInput: String? = null
    ) {
        controller.updateCashInputs(
            openingCashMajorInput = openingCashMajorInput,
            minimumRequiredCashMajorInput = minimumRequiredCashMajorInput
        )
    }

    fun refreshRiskDashboard() {
        viewModelScope.launch {
            controller.recomputeRiskAndCashSnapshot()
        }
    }

    fun checkStaleness() {
        viewModelScope.launch {
            controller.checkStalenessAgainstDatabase()
        }
    }

    companion object {
        fun factory(
            bootstrapRepository: OfflineBootstrapRepository,
            ledgerRepository: LedgerAndReconciliationRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val controller = RiskDashboardWorkflowController(
                    bootstrapRepository = bootstrapRepository,
                    ledgerRepository = ledgerRepository
                )
                return RiskDashboardViewModel(controller) as T
            }
        }
    }
}
