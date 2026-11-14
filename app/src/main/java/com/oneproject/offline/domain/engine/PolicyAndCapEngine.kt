package com.oneproject.offline.domain.engine

import com.oneproject.offline.core.money.ExactMoneyMath
import com.oneproject.offline.data.local.entity.BetSide
import java.math.BigDecimal

/**
 * กลไกคำนวณอัตราจ่าย เพดานรับ/เพดานจ่าย การสืบทอดค่าตั้งค่ารายช่อง (D07)
 * การคำนวณยอดแก้ไขบิลโดยไม่นับยอดเก่าซ้ำ (04_SPEC & 07_TEST_FIXTURES)
 * และการคำนวณยอดจ่ายตามผลรางวัล / กระแสเงินสด (CASH01)
 */
object PolicyAndCapEngine {

    /**
     * กฎ D07:
     * แยกการสืบทอดค่า (Inheritance) รายช่องอย่างชัดเจน:
     * ช่องที่ใน Override เป็น null จะใช้ค่าจาก PolicyVersion มาตรฐาน
     * ช่องที่ใน Override กำหนดค่าไว้จะใช้ค่า Override นั้นโดยไม่ถูกค่ามาตรฐานทับเงียบๆ
     */
    fun resolveEffectiveNumberSidePolicy(
        numberTwoDigit: String,
        side: BetSide,
        defaultPolicy: DomainPolicyDefaults,
        explicitOverride: DomainNumberPolicyOverride?
    ): EffectiveNumberSidePolicy {
        val validNumber = NumberExpansionEngine.validateTwoDigitNumber(numberTwoDigit)
        val baseRateText = when (side) {
            BetSide.TOP -> defaultPolicy.defaultTopRateExact
            BetSide.BOTTOM -> defaultPolicy.defaultBottomRateExact
        }
        val effectiveRateText = explicitOverride?.overrideRateExact ?: baseRateText
        val effectiveRate = ExactMoneyMath.parseExactRate(effectiveRateText)

        val effectiveReceiveCapMinor =
            explicitOverride?.overrideReceiveCapMinor ?: defaultPolicy.defaultReceiveCapMinor
        val effectivePayoutCapMinor =
            explicitOverride?.overridePayoutCapMinor ?: defaultPolicy.defaultPayoutCapMinor
        val isClosed = explicitOverride?.overrideIsClosed ?: false

        return EffectiveNumberSidePolicy(
            numberTwoDigit = validNumber,
            side = side,
            effectiveRateExact = effectiveRate.toPlainString(),
            effectiveRateBigDecimal = effectiveRate,
            effectiveReceiveCapMinor = effectiveReceiveCapMinor,
            effectivePayoutCapMinor = effectivePayoutCapMinor,
            isClosedForOrder = isClosed,
            hasRateOverride = explicitOverride?.overrideRateExact != null,
            hasReceiveCapOverride = explicitOverride?.overrideReceiveCapMinor != null,
            hasPayoutCapOverride = explicitOverride?.overridePayoutCapMinor != null,
            hasClosedOverride = explicitOverride?.overrideIsClosed != null
        )
    }

    /**
     * คำนวณยอดใบเสนอราคา/ตรวจทานรายการในตะกร้า (Cart Quote Calculation)
     * - ปัดเศษ HALF_UP รายบรรทัดที่บันทึกเพียงครั้งเดียว แล้วจึงรวมยอดด้วย checkedAdd
     * - แยกนับ distinctNumberCount กับ lineCount ตาม 05_UI
     * - ตรวจเพดานรับ (Receive Cap) แยกจากเพดานจ่าย (Payout Cap) ตาม 04_SPEC ("Receive cap != payout cap")
     * - ห้ามปรับลดยอด ตัดรายการทิ้ง หรือเปลี่ยนราคาเองเงียบๆ (No silent repricing/dropping/reduction)
     */
    fun evaluateAndQuoteOrderLines(
        rawLines: List<DomainOrderLineInput>,
        defaultPolicy: DomainPolicyDefaults,
        overridesByKey: Map<Pair<String, BetSide>, DomainNumberPolicyOverride>,
        currentActiveCountersByKey: Map<Pair<String, BetSide>, DomainActiveCounterSnapshot>
    ): QuoteEvaluationOutcome {
        if (rawLines.isEmpty()) {
            return QuoteEvaluationOutcome.Rejected(
                code = "EMPTY_ORDER_LINES",
                messageTh = "ไม่มีรายการเลขสำหรับคำนวณใบตรวจทาน"
            )
        }

        val quotedLines = mutableListOf<QuotedPersistedLine>()
        var totalPrincipalMinor = 0L
        var totalProvisionalPayoutMinor = 0L

        for ((index, raw) in rawLines.withIndex()) {
            val validNum = try {
                NumberExpansionEngine.validateTwoDigitNumber(raw.numberTwoDigit)
            } catch (e: IllegalArgumentException) {
                return QuoteEvaluationOutcome.Rejected(
                    code = "INVALID_NUMBER",
                    messageTh = e.message ?: "เลข 2 หลักไม่ถูกต้อง"
                )
            }

            if (raw.amountMinor <= 0L) {
                return QuoteEvaluationOutcome.Rejected(
                    code = "NON_POSITIVE_AMOUNT",
                    messageTh = "รายการที่ ${index + 1} เลข $validNum (${raw.side.thaiLabel}) ต้องมียอดเงินมากกว่า 0 สตางค์"
                )
            }

            val key = validNum to raw.side
            val effectivePolicy = resolveEffectiveNumberSidePolicy(
                numberTwoDigit = validNum,
                side = raw.side,
                defaultPolicy = defaultPolicy,
                explicitOverride = overridesByKey[key]
            )

            if (effectivePolicy.isClosedForOrder) {
                return QuoteEvaluationOutcome.Rejected(
                    code = "NUMBER_CLOSED_FOR_ORDER",
                    messageTh = "เลข $validNum (${raw.side.thaiLabel}) ปิดรับรายการในรุ่นนโยบายปัจจุบัน"
                )
            }

            val linePayoutMinor = ExactMoneyMath.computeProvisionalLinePayoutMinor(
                amountMinor = raw.amountMinor,
                exactRate = effectivePolicy.effectiveRateBigDecimal
            )

            totalPrincipalMinor = ExactMoneyMath.checkedAdd(totalPrincipalMinor, raw.amountMinor)
            totalProvisionalPayoutMinor = ExactMoneyMath.checkedAdd(totalProvisionalPayoutMinor, linePayoutMinor)

            quotedLines.add(
                QuotedPersistedLine(
                    lineNumber = index + 1,
                    numberTwoDigit = validNum,
                    side = raw.side,
                    amountMinor = raw.amountMinor,
                    rateSnapshotExact = effectivePolicy.effectiveRateExact,
                    provisionalPayoutMinor = linePayoutMinor,
                    roundingMode = "HALF_UP"
                )
            )
        }

        // ตรวจเพดานรับ (Receive Cap) และเพดานจ่าย (Payout Cap) ต่อ (number, side)
        val groupedByKey = quotedLines.groupBy { it.numberTwoDigit to it.side }
        for ((key, linesForKey) in groupedByKey) {
            val (num, side) = key
            val effectivePolicy = resolveEffectiveNumberSidePolicy(
                numberTwoDigit = num,
                side = side,
                defaultPolicy = defaultPolicy,
                explicitOverride = overridesByKey[key]
            )
            val currentCounter = currentActiveCountersByKey[key]
            val existingReceiveMinor = currentCounter?.activeReceiveTotalMinor ?: 0L
            val existingPayoutMinor = currentCounter?.activePayoutTotalMinor ?: 0L

            val addedReceiveMinor = ExactMoneyMath.checkedSum(linesForKey.map { it.amountMinor })
            val addedPayoutMinor = ExactMoneyMath.checkedSum(linesForKey.map { it.provisionalPayoutMinor })

            val projectedReceiveMinor = ExactMoneyMath.checkedAdd(existingReceiveMinor, addedReceiveMinor)
            val projectedPayoutMinor = ExactMoneyMath.checkedAdd(existingPayoutMinor, addedPayoutMinor)

            if (projectedReceiveMinor > effectivePolicy.effectiveReceiveCapMinor) {
                return QuoteEvaluationOutcome.Rejected(
                    code = "RECEIVE_CAP_EXCEEDED",
                    messageTh = "เลข $num (${side.thaiLabel}) เกินเพดานรับสูงสุด (ยอดรวมใหม่ $projectedReceiveMinor > เพดานรับ ${effectivePolicy.effectiveReceiveCapMinor} สตางค์)"
                )
            }

            if (projectedPayoutMinor > effectivePolicy.effectivePayoutCapMinor) {
                return QuoteEvaluationOutcome.Rejected(
                    code = "PAYOUT_CAP_EXCEEDED",
                    messageTh = "เลข $num (${side.thaiLabel}) เกินเพดานจ่ายสูงสุด (ยอดจ่ายรวมใหม่ $projectedPayoutMinor > เพดานจ่าย ${effectivePolicy.effectivePayoutCapMinor} สตางค์)"
                )
            }
        }

        val distinctNumbers = quotedLines.map { it.numberTwoDigit }.distinct().size
        return QuoteEvaluationOutcome.ValidQuote(
            quotedLines = quotedLines,
            totalAmountMinor = totalPrincipalMinor,
            totalProvisionalPayoutMinor = totalProvisionalPayoutMinor,
            lineCount = quotedLines.size,
            distinctNumberCount = distinctNumbers
        )
    }

    /**
     * กฎข้อ 01, 04_SPEC และ 07_TEST_FIXTURES ("edit"):
     * คำนวณยอดสะสมหลังแก้ไขบิล:
     * expectedMinor = otherActiveBillsMinor + replacementMinor (ไม่นำ oldMinor มานับซ้ำ)
     */
    fun evaluateBillEditCapCheck(
        otherActiveBillsMinor: Long,
        oldActiveBillRevisionMinor: Long,
        replacementRevisionMinor: Long,
        receiveCapLimitMinor: Long
    ): EditCapEvaluationResult {
        require(otherActiveBillsMinor >= 0L) { "ยอดบิลอื่นต้องไม่ติดลบ" }
        require(oldActiveBillRevisionMinor >= 0L) { "ยอดบิลเดิมต้องไม่ติดลบ" }
        require(replacementRevisionMinor > 0L) { "ยอดบิลที่แก้ไขใหม่ต้องมากกว่า 0 สตางค์" }
        require(receiveCapLimitMinor > 0L) { "เพดานรับต้องมากกว่า 0 สตางค์" }

        // ยอดรวมก่อนแก้ไข = otherActiveBillsMinor + oldActiveBillRevisionMinor
        val previousTotalMinor = ExactMoneyMath.checkedAdd(otherActiveBillsMinor, oldActiveBillRevisionMinor)
        // เมื่อแก้ไข ต้องหัก oldActiveBillRevisionMinor ออกก่อน แล้วบวก replacementRevisionMinor
        val otherAfterExcludingOld = ExactMoneyMath.checkedSubtract(previousTotalMinor, oldActiveBillRevisionMinor)
        val expectedNewTotalMinor = ExactMoneyMath.checkedAdd(otherAfterExcludingOld, replacementRevisionMinor)
        val withinLimit = expectedNewTotalMinor <= receiveCapLimitMinor
        val remainingCapMinor = ExactMoneyMath.checkedSubtract(receiveCapLimitMinor, expectedNewTotalMinor)

        return EditCapEvaluationResult(
            otherActiveBillsMinor = otherActiveBillsMinor,
            excludedOldRevisionMinor = oldActiveBillRevisionMinor,
            replacementRevisionMinor = replacementRevisionMinor,
            receiveCapLimitMinor = receiveCapLimitMinor,
            expectedNewTotalMinor = expectedNewTotalMinor,
            isAllowedWithinCap = withinLimit,
            remainingCapMinor = remainingCapMinor
        )
    }

    /**
     * คำนวณยอดจ่ายรวม (Gross Payout Minor Units) สำหรับผลรางวัล (winningTopTwoDigit, winningBottomTwoDigit)
     * ตรงตาม 04_SPEC ("gross(outcome)=sum matching line payout") และ 07_TEST_FIXTURES ("top31_bottom27_payout_minor")
     */
    fun computeGrossPayoutForOutcomeMinor(
        persistedActiveLines: List<QuotedPersistedLine>,
        winningTopTwoDigit: String,
        winningBottomTwoDigit: String
    ): Long {
        val validTop = NumberExpansionEngine.validateTwoDigitNumber(winningTopTwoDigit)
        val validBottom = NumberExpansionEngine.validateTwoDigitNumber(winningBottomTwoDigit)

        var totalOutcomePayoutMinor = 0L
        for (line in persistedActiveLines) {
            val isMatch = when (line.side) {
                BetSide.TOP -> line.numberTwoDigit == validTop
                BetSide.BOTTOM -> line.numberTwoDigit == validBottom
            }
            if (isMatch) {
                totalOutcomePayoutMinor = ExactMoneyMath.checkedAdd(
                    totalOutcomePayoutMinor,
                    line.provisionalPayoutMinor
                )
            }
        }
        return totalOutcomePayoutMinor
    }

    /**
     * คำนวณสถานะเงินสดตามข้อกำหนด 04_SPEC และ 07_TEST_FIXTURES ("CASH01"):
     * - closing = opening + availableInflows - horizonDueOutflows
     * - shortfall = max(0, minimum - closing)
     */
    fun evaluateCashScenarioPosition(
        openingCashMinor: Long,
        availableInflowsMinor: Long,
        horizonDueOutflowsMinor: Long,
        minimumRequiredCashMinor: Long
    ): CashScenarioPositionResult {
        require(availableInflowsMinor >= 0L) { "กระแสเงินสดรับต้องไม่ติดลบ" }
        require(horizonDueOutflowsMinor >= 0L) { "กระแสเงินสดจ่ายต้องไม่ติดลบ" }
        require(minimumRequiredCashMinor >= 0L) { "เงินสดขั้นต่ำที่ต้องดำรงต้องไม่ติดลบ" }

        val afterInflows = ExactMoneyMath.checkedAdd(openingCashMinor, availableInflowsMinor)
        val closingCashMinor = ExactMoneyMath.checkedSubtract(afterInflows, horizonDueOutflowsMinor)

        val rawGap = ExactMoneyMath.checkedSubtract(minimumRequiredCashMinor, closingCashMinor)
        val shortfallMinor = if (rawGap > 0L) rawGap else 0L

        return CashScenarioPositionResult(
            openingCashMinor = openingCashMinor,
            availableInflowsMinor = availableInflowsMinor,
            horizonDueOutflowsMinor = horizonDueOutflowsMinor,
            closingCashMinor = closingCashMinor,
            minimumRequiredCashMinor = minimumRequiredCashMinor,
            shortfallMinor = shortfallMinor,
            hasShortfallAlert = shortfallMinor > 0L
        )
    }
}

data class DomainPolicyDefaults(
    val policyVersion: Long,
    val defaultTopRateExact: String,
    val defaultBottomRateExact: String,
    val defaultReceiveCapMinor: Long,
    val defaultPayoutCapMinor: Long
)

data class DomainNumberPolicyOverride(
    val numberTwoDigit: String,
    val side: BetSide,
    val overrideRateExact: String? = null,
    val overrideReceiveCapMinor: Long? = null,
    val overridePayoutCapMinor: Long? = null,
    val overrideIsClosed: Boolean? = null
)

data class EffectiveNumberSidePolicy(
    val numberTwoDigit: String,
    val side: BetSide,
    val effectiveRateExact: String,
    val effectiveRateBigDecimal: BigDecimal,
    val effectiveReceiveCapMinor: Long,
    val effectivePayoutCapMinor: Long,
    val isClosedForOrder: Boolean,
    val hasRateOverride: Boolean,
    val hasReceiveCapOverride: Boolean,
    val hasPayoutCapOverride: Boolean,
    val hasClosedOverride: Boolean
)

data class DomainOrderLineInput(
    val numberTwoDigit: String,
    val side: BetSide,
    val amountMinor: Long
)

data class DomainActiveCounterSnapshot(
    val numberTwoDigit: String,
    val side: BetSide,
    val activeReceiveTotalMinor: Long,
    val activePayoutTotalMinor: Long
)

data class QuotedPersistedLine(
    val lineNumber: Int,
    val numberTwoDigit: String,
    val side: BetSide,
    val amountMinor: Long,
    val rateSnapshotExact: String,
    val provisionalPayoutMinor: Long,
    val roundingMode: String = "HALF_UP"
)

sealed interface QuoteEvaluationOutcome {
    data class ValidQuote(
        val quotedLines: List<QuotedPersistedLine>,
        val totalAmountMinor: Long,
        val totalProvisionalPayoutMinor: Long,
        val lineCount: Int,
        val distinctNumberCount: Int
    ) : QuoteEvaluationOutcome

    data class Rejected(
        val code: String,
        val messageTh: String
    ) : QuoteEvaluationOutcome
}

data class EditCapEvaluationResult(
    val otherActiveBillsMinor: Long,
    val excludedOldRevisionMinor: Long,
    val replacementRevisionMinor: Long,
    val receiveCapLimitMinor: Long,
    val expectedNewTotalMinor: Long,
    val isAllowedWithinCap: Boolean,
    val remainingCapMinor: Long
)

data class CashScenarioPositionResult(
    val openingCashMinor: Long,
    val availableInflowsMinor: Long,
    val horizonDueOutflowsMinor: Long,
    val closingCashMinor: Long,
    val minimumRequiredCashMinor: Long,
    val shortfallMinor: Long,
    val hasShortfallAlert: Boolean
)
