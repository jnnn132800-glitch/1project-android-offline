package com.oneproject.offline.domain.engine

import com.oneproject.offline.core.money.ExactMoneyMath
import com.oneproject.offline.data.local.entity.BetSide
import com.oneproject.offline.data.local.entity.TransferResponseStatus

/**
 * เอนจินคำนวณความเสี่ยงรายผลลัพธ์ (100 × 100 Valid Outcomes — D06) และสถานการณ์เงินสด (Phase 09)
 *
 * กฎเหล็กตาม 01_SYSTEM_INSTRUCTIONS และ 04_SPEC:
 * 1. gross(outcome) = ผลรวมยอดจ่ายคาดการณ์ (provisionalPayoutMinor) ของบรรทัดบิลที่ ACTIVE และตรงกับผลลัพธ์นั้น
 * 2. เฉพาะรายการตีออกที่คู่สัญญายอมรับแล้ว (ACCEPTED หรือยอดที่ยอมรับจริงใน PARTIAL) และตรงกับผลลัพธ์นั้นเท่านั้นที่นำมาหักเป็น acceptedRecoverable(outcome)
 * 3. รายการตีออกที่อยู่ในสถานะ PENDING หรือ REJECTED มีผลลดความเสี่ยงเท่ากับ 0 สตางค์ (Pending transfers reduce no exposure)
 * 4. คำนวณ net(outcome) = max(0, gross(outcome) - acceptedRecoverable(outcome)) แยกตามแต่ละผลลัพธ์ก่อน แล้วจึงหาค่าสูงสุด (Net Maximum over Valid Outcomes)
 *    ห้ามนำค่าสูงสุดคนละเลขมาลบกันข้ามผลลัพธ์ (Do not subtract unrelated maxima)
 * 5. สถานการณ์เงินสดมาตรฐาน (Default Cash Scenario) ไม่นับยอดรอรับคืนจากคู่สัญญาเป็นเงินสดพร้อมจ่าย และไม่นับลูกหนี้ค่าบิลค้างเก็บซ้ำซ้อนกับเงินสดรับจริง
 * 6. แสดงเวลาคำนวณในเครื่อง (local as-of timestamp) และบล็อกการตัดสินใจเมื่อข้อมูลความเสี่ยงไม่เป็นปัจจุบัน (Stale Risk Blocking)
 */
object RiskOutcomeAndCashScenarioEngine {

    private val ALL_TWO_DIGIT_NUMBERS: List<String> = (0..99).map { "%02d".format(it) }

    fun evaluateValidOutcomesRiskMaxima(
        roundId: String,
        policyVersion: Long,
        defaultTopPayoutCapMinor: Long,
        defaultBottomPayoutCapMinor: Long,
        payoutCapOverridesByKey: Map<Pair<String, BetSide>, Long>,
        activeBillLines: List<RiskActiveBillLineInput>,
        transferLines: List<RiskTransferLineInput>,
        computedAsOfEpochMs: Long,
        latestDataMutationEpochMs: Long
    ): RoundRiskAssessmentSnapshot {
        val grossByKey = mutableMapOf<Pair<String, BetSide>, Long>()
        val receiveByKey = mutableMapOf<Pair<String, BetSide>, Long>()

        for (line in activeBillLines) {
            val validNum = NumberExpansionEngine.validateTwoDigitNumber(line.numberTwoDigit)
            val key = validNum to line.side
            grossByKey[key] = ExactMoneyMath.checkedAdd(
                grossByKey[key] ?: 0L,
                line.provisionalPayoutMinor
            )
            receiveByKey[key] = ExactMoneyMath.checkedAdd(
                receiveByKey[key] ?: 0L,
                line.amountMinor
            )
        }

        val acceptedRecoverableByKey = mutableMapOf<Pair<String, BetSide>, Long>()
        val pendingSubmittedAmountByKey = mutableMapOf<Pair<String, BetSide>, Long>()
        val rejectedSubmittedAmountByKey = mutableMapOf<Pair<String, BetSide>, Long>()

        var totalPendingSubmittedAmountMinor = 0L
        var totalAcceptedRecoverablePayoutMinor = 0L

        for (tLine in transferLines) {
            val validNum = NumberExpansionEngine.validateTwoDigitNumber(tLine.numberTwoDigit)
            val key = validNum to tLine.side

            val isBatchEligible = tLine.batchStatus == TransferResponseStatus.ACCEPTED.name ||
                tLine.batchStatus == TransferResponseStatus.PARTIAL.name
            val isLineEligible = tLine.lineStatus == TransferResponseStatus.ACCEPTED.name ||
                tLine.lineStatus == TransferResponseStatus.PARTIAL.name

            if (isBatchEligible && isLineEligible && tLine.acceptedAmountMinor > 0L && tLine.acceptedRecoverablePayoutMinor > 0L) {
                acceptedRecoverableByKey[key] = ExactMoneyMath.checkedAdd(
                    acceptedRecoverableByKey[key] ?: 0L,
                    tLine.acceptedRecoverablePayoutMinor
                )
                totalAcceptedRecoverablePayoutMinor = ExactMoneyMath.checkedAdd(
                    totalAcceptedRecoverablePayoutMinor,
                    tLine.acceptedRecoverablePayoutMinor
                )
            } else if (tLine.batchStatus == TransferResponseStatus.PENDING.name ||
                tLine.lineStatus == TransferResponseStatus.PENDING.name
            ) {
                pendingSubmittedAmountByKey[key] = ExactMoneyMath.checkedAdd(
                    pendingSubmittedAmountByKey[key] ?: 0L,
                    tLine.submittedAmountMinor
                )
                totalPendingSubmittedAmountMinor = ExactMoneyMath.checkedAdd(
                    totalPendingSubmittedAmountMinor,
                    tLine.submittedAmountMinor
                )
            } else {
                rejectedSubmittedAmountByKey[key] = ExactMoneyMath.checkedAdd(
                    rejectedSubmittedAmountByKey[key] ?: 0L,
                    tLine.submittedAmountMinor
                )
            }
        }

        val topCells = ArrayList<NumberSideRiskCell>(100)
        val bottomCells = ArrayList<NumberSideRiskCell>(100)

        for (num in ALL_TWO_DIGIT_NUMBERS) {
            topCells.add(
                buildCell(
                    numberTwoDigit = num,
                    side = BetSide.TOP,
                    activeReceiveMinor = receiveByKey[num to BetSide.TOP] ?: 0L,
                    grossPayoutMinor = grossByKey[num to BetSide.TOP] ?: 0L,
                    acceptedRecoverableMinor = acceptedRecoverableByKey[num to BetSide.TOP] ?: 0L,
                    pendingSubmittedMinor = pendingSubmittedAmountByKey[num to BetSide.TOP] ?: 0L,
                    rejectedSubmittedMinor = rejectedSubmittedAmountByKey[num to BetSide.TOP] ?: 0L,
                    effectivePayoutCapMinor = payoutCapOverridesByKey[num to BetSide.TOP] ?: defaultTopPayoutCapMinor
                )
            )
            bottomCells.add(
                buildCell(
                    numberTwoDigit = num,
                    side = BetSide.BOTTOM,
                    activeReceiveMinor = receiveByKey[num to BetSide.BOTTOM] ?: 0L,
                    grossPayoutMinor = grossByKey[num to BetSide.BOTTOM] ?: 0L,
                    acceptedRecoverableMinor = acceptedRecoverableByKey[num to BetSide.BOTTOM] ?: 0L,
                    pendingSubmittedMinor = pendingSubmittedAmountByKey[num to BetSide.BOTTOM] ?: 0L,
                    rejectedSubmittedMinor = rejectedSubmittedAmountByKey[num to BetSide.BOTTOM] ?: 0L,
                    effectivePayoutCapMinor = payoutCapOverridesByKey[num to BetSide.BOTTOM] ?: defaultBottomPayoutCapMinor
                )
            )
        }

        val maxGrossTopCell = topCells.maxWith(
            compareBy<NumberSideRiskCell> { it.grossPayoutMinor }
                .thenBy { -it.numberTwoDigit.toInt() }
        )
        val maxGrossBottomCell = bottomCells.maxWith(
            compareBy<NumberSideRiskCell> { it.grossPayoutMinor }
                .thenBy { -it.numberTwoDigit.toInt() }
        )

        val maxNetTopCell = topCells.maxWith(
            compareBy<NumberSideRiskCell> { it.netPayoutMinor }
                .thenBy { -it.numberTwoDigit.toInt() }
        )
        val maxNetBottomCell = bottomCells.maxWith(
            compareBy<NumberSideRiskCell> { it.netPayoutMinor }
                .thenBy { -it.numberTwoDigit.toInt() }
        )

        // ประเมินปริภูมิผลลัพธ์ 100 × 100 (10,000 ผลลัพธ์ที่เป็นไปได้ตาม D06) แยกกันระหว่าง Gross และ Net
        var worstGrossJointOutcome = JointOutcomeRiskPoint(
            winningTopTwoDigit = "00",
            winningBottomTwoDigit = "00",
            grossTopMinor = topCells[0].grossPayoutMinor,
            grossBottomMinor = bottomCells[0].grossPayoutMinor,
            acceptedRecoverableTopMinor = topCells[0].acceptedRecoverableMinor,
            acceptedRecoverableBottomMinor = bottomCells[0].acceptedRecoverableMinor,
            netTopMinor = topCells[0].netPayoutMinor,
            netBottomMinor = bottomCells[0].netPayoutMinor,
            jointGrossPayoutMinor = ExactMoneyMath.checkedAdd(
                topCells[0].grossPayoutMinor,
                bottomCells[0].grossPayoutMinor
            ),
            jointNetPayoutMinor = ExactMoneyMath.checkedAdd(
                topCells[0].netPayoutMinor,
                bottomCells[0].netPayoutMinor
            )
        )

        var worstNetJointOutcome = worstGrossJointOutcome
        var validOutcomeCount = 0

        for (tCell in topCells) {
            for (bCell in bottomCells) {
                validOutcomeCount += 1
                val jointGross = ExactMoneyMath.checkedAdd(tCell.grossPayoutMinor, bCell.grossPayoutMinor)
                val jointNet = ExactMoneyMath.checkedAdd(tCell.netPayoutMinor, bCell.netPayoutMinor)

                if (jointGross > worstGrossJointOutcome.jointGrossPayoutMinor) {
                    worstGrossJointOutcome = JointOutcomeRiskPoint(
                        winningTopTwoDigit = tCell.numberTwoDigit,
                        winningBottomTwoDigit = bCell.numberTwoDigit,
                        grossTopMinor = tCell.grossPayoutMinor,
                        grossBottomMinor = bCell.grossPayoutMinor,
                        acceptedRecoverableTopMinor = tCell.acceptedRecoverableMinor,
                        acceptedRecoverableBottomMinor = bCell.acceptedRecoverableMinor,
                        netTopMinor = tCell.netPayoutMinor,
                        netBottomMinor = bCell.netPayoutMinor,
                        jointGrossPayoutMinor = jointGross,
                        jointNetPayoutMinor = jointNet
                    )
                }

                if (jointNet > worstNetJointOutcome.jointNetPayoutMinor) {
                    worstNetJointOutcome = JointOutcomeRiskPoint(
                        winningTopTwoDigit = tCell.numberTwoDigit,
                        winningBottomTwoDigit = bCell.numberTwoDigit,
                        grossTopMinor = tCell.grossPayoutMinor,
                        grossBottomMinor = bCell.grossPayoutMinor,
                        acceptedRecoverableTopMinor = tCell.acceptedRecoverableMinor,
                        acceptedRecoverableBottomMinor = bCell.acceptedRecoverableMinor,
                        netTopMinor = tCell.netPayoutMinor,
                        netBottomMinor = bCell.netPayoutMinor,
                        jointGrossPayoutMinor = jointGross,
                        jointNetPayoutMinor = jointNet
                    )
                }
            }
        }

        val allCells = topCells + bottomCells
        val capBreachCount = allCells.count { it.alertStatus == RiskAlertStatus.CRITICAL_CAP_BREACH }
        val nearCapWarningCount = allCells.count { it.alertStatus == RiskAlertStatus.WARNING_NEAR_CAP }
        val isStale = latestDataMutationEpochMs > computedAsOfEpochMs

        return RoundRiskAssessmentSnapshot(
            roundId = roundId,
            policyVersion = policyVersion,
            evaluatedValidOutcomeCount = validOutcomeCount,
            isDemoOutcomeSpaceD06 = true,
            computedAsOfEpochMs = computedAsOfEpochMs,
            latestDataMutationEpochMs = latestDataMutationEpochMs,
            isStale = isStale,
            isDecisionBlockedByStaleRisk = isStale,
            maxGrossTopCell = maxGrossTopCell,
            maxGrossBottomCell = maxGrossBottomCell,
            maxNetTopCell = maxNetTopCell,
            maxNetBottomCell = maxNetBottomCell,
            worstGrossJointOutcome = worstGrossJointOutcome,
            worstNetJointOutcome = worstNetJointOutcome,
            totalPendingSubmittedAmountMinor = totalPendingSubmittedAmountMinor,
            pendingExposureReductionMinor = 0L, // กฎเหล็ก: Pending ลดความเสี่ยงได้ 0 เสมอ
            totalAcceptedRecoverablePayoutMinor = totalAcceptedRecoverablePayoutMinor,
            capBreachCellCount = capBreachCount,
            nearCapWarningCellCount = nearCapWarningCount,
            topCells = topCells,
            bottomCells = bottomCells
        )
    }

    /**
     * ประเมินสถานการณ์กระแสเงินสดของงวด (Cash Scenarios)
     * - สถานการณ์มาตรฐาน (Default Conservative Scenario):
     *   ไม่นับลูกหนี้ค่าบิลที่ยังเก็บไม่ได้เป็นเงินสดรับ และไม่นับยอดรับคืนจากคู่สัญญาเป็นเงินสดพร้อมจ่าย
     *   (outflow = paidOutflowsMinor + worstGrossJointOutcomePayoutMinor)
     * - สถานการณ์อ้างอิงเมื่อหักยอดรับคืนที่ตรงผลลัพธ์ (Outcome-Matched Recoverable Reference Scenario):
     *   แสดงเพื่อเปรียบเทียบกรณีคู่สัญญาชำระตามสัญญา (outflow = paidOutflowsMinor + worstNetJointOutcomePayoutMinor)
     *   พร้อมคำเตือนว่าสิทธิรับคืนตามสัญญาไม่ใช่เงินสดในมือที่รับประกันแล้ว
     */
    fun evaluateRoundCashScenarios(
        openingCashMinor: Long,
        collectedCashInflowsMinor: Long,
        uncollectedBillReceivableMinor: Long,
        paidOutflowsMinor: Long,
        minimumRequiredCashMinor: Long,
        worstGrossOutcomePayoutMinor: Long,
        worstNetOutcomePayoutMinor: Long
    ): RoundCashScenariosComparison {
        val grossHorizonOutflowMinor = ExactMoneyMath.checkedAdd(
            paidOutflowsMinor,
            worstGrossOutcomePayoutMinor
        )
        val netHorizonOutflowMinor = ExactMoneyMath.checkedAdd(
            paidOutflowsMinor,
            worstNetOutcomePayoutMinor
        )

        val defaultConservativeScenario = PolicyAndCapEngine.evaluateCashScenarioPosition(
            openingCashMinor = openingCashMinor,
            availableInflowsMinor = collectedCashInflowsMinor,
            horizonDueOutflowsMinor = grossHorizonOutflowMinor,
            minimumRequiredCashMinor = minimumRequiredCashMinor
        )

        val matchedRecoverableReferenceScenario = PolicyAndCapEngine.evaluateCashScenarioPosition(
            openingCashMinor = openingCashMinor,
            availableInflowsMinor = collectedCashInflowsMinor,
            horizonDueOutflowsMinor = netHorizonOutflowMinor,
            minimumRequiredCashMinor = minimumRequiredCashMinor
        )

        return RoundCashScenariosComparison(
            openingCashMinor = openingCashMinor,
            collectedCashInflowsMinor = collectedCashInflowsMinor,
            excludedUncollectedReceivableMinor = uncollectedBillReceivableMinor,
            alreadyPaidOutflowsMinor = paidOutflowsMinor,
            minimumRequiredCashMinor = minimumRequiredCashMinor,
            defaultConservativeScenario = defaultConservativeScenario,
            matchedRecoverableReferenceScenario = matchedRecoverableReferenceScenario
        )
    }

    private fun buildCell(
        numberTwoDigit: String,
        side: BetSide,
        activeReceiveMinor: Long,
        grossPayoutMinor: Long,
        acceptedRecoverableMinor: Long,
        pendingSubmittedMinor: Long,
        rejectedSubmittedMinor: Long,
        effectivePayoutCapMinor: Long
    ): NumberSideRiskCell {
        val netPayoutMinor = if (grossPayoutMinor > acceptedRecoverableMinor) {
            ExactMoneyMath.checkedSubtract(grossPayoutMinor, acceptedRecoverableMinor)
        } else {
            0L
        }

        val alertStatus = when {
            effectivePayoutCapMinor > 0L && netPayoutMinor > effectivePayoutCapMinor ->
                RiskAlertStatus.CRITICAL_CAP_BREACH
            effectivePayoutCapMinor > 0L &&
                ExactMoneyMath.checkedMultiply(netPayoutMinor, 100L) >=
                ExactMoneyMath.checkedMultiply(effectivePayoutCapMinor, 80L) ->
                RiskAlertStatus.WARNING_NEAR_CAP
            else -> RiskAlertStatus.NORMAL
        }

        return NumberSideRiskCell(
            numberTwoDigit = numberTwoDigit,
            side = side,
            activeReceiveMinor = activeReceiveMinor,
            grossPayoutMinor = grossPayoutMinor,
            acceptedRecoverableMinor = acceptedRecoverableMinor,
            pendingSubmittedMinor = pendingSubmittedMinor,
            rejectedSubmittedMinor = rejectedSubmittedMinor,
            netPayoutMinor = netPayoutMinor,
            effectivePayoutCapMinor = effectivePayoutCapMinor,
            alertStatus = alertStatus
        )
    }
}

enum class RiskAlertStatus(val thaiLabel: String, val codeText: String) {
    NORMAL("อยู่ในเกณฑ์ปกติ", "NORMAL"),
    WARNING_NEAR_CAP("ใกล้เต็มเพดานจ่าย (>=80%)", "WARN_80_PCT"),
    CRITICAL_CAP_BREACH("เกินเพดานจ่ายสุทธิ (>100%)", "CAP_BREACH")
}

data class RiskActiveBillLineInput(
    val numberTwoDigit: String,
    val side: BetSide,
    val amountMinor: Long,
    val provisionalPayoutMinor: Long
)

data class RiskTransferLineInput(
    val batchId: String,
    val batchStatus: String,
    val lineStatus: String,
    val numberTwoDigit: String,
    val side: BetSide,
    val submittedAmountMinor: Long,
    val acceptedAmountMinor: Long,
    val acceptedRecoverablePayoutMinor: Long
)

data class NumberSideRiskCell(
    val numberTwoDigit: String,
    val side: BetSide,
    val activeReceiveMinor: Long,
    val grossPayoutMinor: Long,
    val acceptedRecoverableMinor: Long,
    val pendingSubmittedMinor: Long,
    val rejectedSubmittedMinor: Long,
    val netPayoutMinor: Long,
    val effectivePayoutCapMinor: Long,
    val alertStatus: RiskAlertStatus
)

data class JointOutcomeRiskPoint(
    val winningTopTwoDigit: String,
    val winningBottomTwoDigit: String,
    val grossTopMinor: Long,
    val grossBottomMinor: Long,
    val acceptedRecoverableTopMinor: Long,
    val acceptedRecoverableBottomMinor: Long,
    val netTopMinor: Long,
    val netBottomMinor: Long,
    val jointGrossPayoutMinor: Long,
    val jointNetPayoutMinor: Long
)

data class RoundRiskAssessmentSnapshot(
    val roundId: String,
    val policyVersion: Long,
    val evaluatedValidOutcomeCount: Int,
    val isDemoOutcomeSpaceD06: Boolean,
    val computedAsOfEpochMs: Long,
    val latestDataMutationEpochMs: Long,
    val isStale: Boolean,
    val isDecisionBlockedByStaleRisk: Boolean,
    val maxGrossTopCell: NumberSideRiskCell,
    val maxGrossBottomCell: NumberSideRiskCell,
    val maxNetTopCell: NumberSideRiskCell,
    val maxNetBottomCell: NumberSideRiskCell,
    val worstGrossJointOutcome: JointOutcomeRiskPoint,
    val worstNetJointOutcome: JointOutcomeRiskPoint,
    val totalPendingSubmittedAmountMinor: Long,
    val pendingExposureReductionMinor: Long,
    val totalAcceptedRecoverablePayoutMinor: Long,
    val capBreachCellCount: Int,
    val nearCapWarningCellCount: Int,
    val topCells: List<NumberSideRiskCell>,
    val bottomCells: List<NumberSideRiskCell>
)

data class RoundCashScenariosComparison(
    val openingCashMinor: Long,
    val collectedCashInflowsMinor: Long,
    val excludedUncollectedReceivableMinor: Long,
    val alreadyPaidOutflowsMinor: Long,
    val minimumRequiredCashMinor: Long,
    val defaultConservativeScenario: CashScenarioPositionResult,
    val matchedRecoverableReferenceScenario: CashScenarioPositionResult
)
