package com.oneproject.offline.data.repository

import com.oneproject.offline.core.money.ExactMoneyMath
import com.oneproject.offline.data.local.dao.ApproveResultRevisionRequest
import com.oneproject.offline.data.local.dao.AtomicBillCommitRequest
import com.oneproject.offline.data.local.dao.AtomicBillEditRequest
import com.oneproject.offline.data.local.dao.AtomicCommitResult
import com.oneproject.offline.data.local.dao.AuthoritativeCommitDao
import com.oneproject.offline.data.local.dao.CartAndQuoteDao
import com.oneproject.offline.data.local.dao.CartLineDraft
import com.oneproject.offline.data.local.dao.ClosedRoundAdjustmentResult
import com.oneproject.offline.data.local.dao.PolicyUpdateResult
import com.oneproject.offline.data.local.dao.ResultApprovalOutcome
import com.oneproject.offline.data.local.dao.ResultEntitlementPreviewOutcome
import com.oneproject.offline.data.local.dao.RoundTransitionResult
import com.oneproject.offline.data.local.entity.ApprovalRecordEntity
import com.oneproject.offline.data.local.entity.BetSide
import com.oneproject.offline.data.local.entity.BillEntity
import com.oneproject.offline.data.local.entity.BillNumberSideLineEntity
import com.oneproject.offline.data.local.entity.BillRevisionEntity
import com.oneproject.offline.data.local.entity.BillStatus
import com.oneproject.offline.data.local.entity.CartEntity
import com.oneproject.offline.data.local.entity.CartLineEntity
import com.oneproject.offline.data.local.entity.DurableOperationEntity
import com.oneproject.offline.data.local.entity.NumberPolicyOverrideEntity
import com.oneproject.offline.data.local.entity.NumberSideCounterEntity
import com.oneproject.offline.data.local.entity.PolicyVersionEntity
import com.oneproject.offline.data.local.entity.QuoteSnapshotEntity
import com.oneproject.offline.data.local.entity.ResultRevisionEntity
import com.oneproject.offline.data.local.entity.RoundEntity
import com.oneproject.offline.data.local.entity.RoundStatus
import com.oneproject.offline.data.local.entity.WinningEntitlementEntity
import com.oneproject.offline.domain.engine.DomainActiveCounterSnapshot
import com.oneproject.offline.domain.engine.DomainNumberPolicyOverride
import kotlinx.coroutines.flow.Flow

/**
 * Repository หลักสำหรับจัดการตะกร้าในเครื่อง ใบตรวจทาน บิล/ประวัติการแก้ไข นโยบาย การจัดการงวด และการประกาศผลรางวัล/รายงานเจาะลึก
 */
class AuthoritativeOrderRepository(
    private val cartAndQuoteDao: CartAndQuoteDao,
    private val commitDao: AuthoritativeCommitDao
) {
    fun observeCartForRound(roundId: String): Flow<CartEntity?> {
        return cartAndQuoteDao.observeCartForRound(roundId)
    }

    fun observeCartLines(cartId: String): Flow<List<CartLineEntity>> {
        return cartAndQuoteDao.observeCartLines(cartId)
    }

    suspend fun getCartLinesSnapshot(cartId: String): List<CartLineEntity> {
        return cartAndQuoteDao.getCartLines(cartId)
    }

    suspend fun appendLinesToCart(
        cartId: String,
        roundId: String,
        customerNoteTh: String,
        newLines: List<CartLineDraft>,
        nowEpochMs: Long
    ) {
        cartAndQuoteDao.appendLinesToDurableCart(
            cartId = cartId,
            roundId = roundId,
            customerNoteTh = customerNoteTh,
            newLines = newLines,
            nowEpochMs = nowEpochMs
        )
    }

    suspend fun updateCartLineAmount(
        cartId: String,
        cartLineId: Long,
        newAmountMinor: Long
    ) {
        require(newAmountMinor > 0L) { "จำนวนเงินต้องมากกว่า 0 สตางค์" }
        cartAndQuoteDao.updateCartLineAmount(
            cartId = cartId,
            cartLineId = cartLineId,
            newAmountMinor = newAmountMinor
        )
    }

    suspend fun deleteCartLine(
        cartId: String,
        cartLineId: Long
    ) {
        cartAndQuoteDao.deleteCartLine(cartId = cartId, cartLineId = cartLineId)
    }

    suspend fun clearCart(cartId: String) {
        cartAndQuoteDao.clearCartLines(cartId)
    }

    suspend fun saveQuoteSnapshot(quote: QuoteSnapshotEntity) {
        cartAndQuoteDao.insertQuoteSnapshot(quote)
    }

    suspend fun getRoundSnapshot(roundId: String): RoundEntity? {
        return commitDao.getRoundById(roundId)
    }

    suspend fun getAllRounds(): List<RoundEntity> {
        return commitDao.getAllRounds()
    }

    suspend fun getPolicyVersionSnapshot(roundId: String, policyVersion: Long): PolicyVersionEntity? {
        return commitDao.getPolicyVersion(roundId, policyVersion)
    }

    suspend fun getPolicyHistoryForRound(roundId: String): List<PolicyVersionEntity> {
        return commitDao.getPolicyHistoryForRound(roundId)
    }

    suspend fun getOverridesForPolicyVersion(
        roundId: String,
        policyVersion: Long
    ): List<NumberPolicyOverrideEntity> {
        return commitDao.getOverridesForPolicyVersion(roundId, policyVersion)
    }

    suspend fun getOverridesForKeys(
        roundId: String,
        policyVersion: Long,
        keys: Set<Pair<String, BetSide>>
    ): Map<Pair<String, BetSide>, DomainNumberPolicyOverride> {
        val result = mutableMapOf<Pair<String, BetSide>, DomainNumberPolicyOverride>()
        for ((num, side) in keys) {
            val entity = commitDao.getNumberPolicyOverride(
                roundId = roundId,
                policyVersion = policyVersion,
                numberTwoDigit = num,
                side = side.name
            )
            if (entity != null) {
                result[num to side] = DomainNumberPolicyOverride(
                    numberTwoDigit = num,
                    side = side,
                    overrideRateExact = entity.overrideRateExact,
                    overrideReceiveCapMinor = entity.overrideReceiveCapMinor,
                    overridePayoutCapMinor = entity.overridePayoutCapMinor,
                    overrideIsClosed = entity.overrideIsClosed
                )
            }
        }
        return result
    }

    suspend fun getActiveCountersMapForRound(
        roundId: String
    ): Map<Pair<String, BetSide>, DomainActiveCounterSnapshot> {
        val rows = commitDao.getAllCountersForRound(roundId)
        val map = mutableMapOf<Pair<String, BetSide>, DomainActiveCounterSnapshot>()
        for (row in rows) {
            val side = BetSide.entries.firstOrNull { it.name == row.side } ?: continue
            map[row.numberTwoDigit to side] = DomainActiveCounterSnapshot(
                numberTwoDigit = row.numberTwoDigit,
                side = side,
                activeReceiveTotalMinor = row.activeReceiveTotalMinor,
                activePayoutTotalMinor = row.activePayoutTotalMinor
            )
        }
        return map
    }

    suspend fun findCompletedOrRejectedOperation(operationKey: String): DurableOperationEntity? {
        return commitDao.getDurableOperation(operationKey)
    }

    suspend fun commitNewBillAtomically(request: AtomicBillCommitRequest): AtomicCommitResult {
        return commitDao.executeAtomicBillCommit(request)
    }

    suspend fun commitBillEditRevisionAtomically(request: AtomicBillEditRequest): AtomicCommitResult {
        return commitDao.executeAtomicBillEditRevision(request)
    }

    suspend fun cancelBillAtomically(
        operationKey: String,
        payloadSha256: String,
        billId: String,
        cancelReasonTh: String,
        actorId: String,
        nowEpochMs: Long
    ): AtomicCommitResult {
        return commitDao.executeAtomicBillCancel(
            operationKey = operationKey,
            payloadSha256 = payloadSha256,
            billId = billId,
            cancelReasonTh = cancelReasonTh,
            actorId = actorId,
            nowEpochMs = nowEpochMs
        )
    }

    suspend fun searchBillsInRound(roundId: String, searchQuery: String): List<BillEntity> {
        return commitDao.searchBillsInRound(roundId = roundId, searchQuery = searchQuery.trim())
    }

    suspend fun getBillWithActiveLines(billId: String): BillWithHistorySnapshot? {
        val bill = commitDao.getBillById(billId) ?: return null
        val revisions = commitDao.getRevisionsForBill(billId)
        val activeLines = bill.activeRevisionId?.let { commitDao.getLinesForRevision(it) }.orEmpty()
        return BillWithHistorySnapshot(
            bill = bill,
            revisions = revisions,
            activeLines = activeLines
        )
    }

    suspend fun getLinesForSpecificRevision(revisionId: String): List<BillNumberSideLineEntity> {
        return commitDao.getLinesForRevision(revisionId)
    }

    suspend fun getCounterForNumberSide(
        roundId: String,
        numberTwoDigit: String,
        side: String
    ): NumberSideCounterEntity? {
        return commitDao.getNumberSideCounter(roundId, numberTwoDigit, side)
    }

    suspend fun publishNewDefaultPolicyVersion(
        roundId: String,
        newTopRateExact: String,
        newBottomRateExact: String,
        newReceiveCapMinor: Long,
        newPayoutCapMinor: Long,
        applyAllClearRateAndCapOverrides: Boolean,
        resetOpenCloseOverrides: Boolean,
        changeReasonTh: String,
        actorId: String,
        nowEpochMs: Long
    ): PolicyUpdateResult {
        return commitDao.publishNewDefaultPolicyVersionTransaction(
            roundId = roundId,
            newTopRateExact = newTopRateExact,
            newBottomRateExact = newBottomRateExact,
            newReceiveCapMinor = newReceiveCapMinor,
            newPayoutCapMinor = newPayoutCapMinor,
            applyAllClearRateAndCapOverrides = applyAllClearRateAndCapOverrides,
            resetOpenCloseOverrides = resetOpenCloseOverrides,
            changeReasonTh = changeReasonTh,
            actorId = actorId,
            nowEpochMs = nowEpochMs
        )
    }

    suspend fun savePerNumberExplicitOverride(
        roundId: String,
        numberTwoDigit: String,
        side: BetSide,
        overrideRateExact: String?,
        overrideReceiveCapMinor: Long?,
        overridePayoutCapMinor: Long?,
        overrideIsClosed: Boolean?,
        overrideReasonTh: String,
        actorId: String,
        nowEpochMs: Long
    ): PolicyUpdateResult {
        return commitDao.savePerNumberExplicitOverrideTransaction(
            roundId = roundId,
            numberTwoDigit = numberTwoDigit,
            side = side.name,
            overrideRateExact = overrideRateExact,
            overrideReceiveCapMinor = overrideReceiveCapMinor,
            overridePayoutCapMinor = overridePayoutCapMinor,
            overrideIsClosed = overrideIsClosed,
            overrideReasonTh = overrideReasonTh,
            actorId = actorId,
            nowEpochMs = nowEpochMs
        )
    }

    suspend fun transitionRoundStatus(
        roundId: String,
        targetStatus: RoundStatus,
        reasonTh: String,
        actorId: String,
        nowEpochMs: Long
    ): RoundTransitionResult {
        return commitDao.transitionRoundStatusTransaction(
            roundId = roundId,
            targetStatus = targetStatus,
            reasonTh = reasonTh,
            actorId = actorId,
            nowEpochMs = nowEpochMs
        )
    }

    suspend fun recordClosedRoundApprovedAdjustment(
        approvalId: String,
        roundId: String,
        targetBillId: String,
        approvedByActorId: String,
        adjustmentReasonTh: String,
        nowEpochMs: Long
    ): ClosedRoundAdjustmentResult {
        return commitDao.recordClosedRoundApprovedAdjustmentTransaction(
            approvalId = approvalId,
            roundId = roundId,
            targetBillId = targetBillId,
            approvedByActorId = approvedByActorId,
            adjustmentReasonTh = adjustmentReasonTh,
            nowEpochMs = nowEpochMs
        )
    }

    suspend fun getApprovalsForRound(roundId: String): List<ApprovalRecordEntity> {
        return commitDao.getApprovalsForRound(roundId)
    }

    // =========================================================================
    // Phase 07 — Result Revision Approval, Idempotent Winning Entitlement & Reports Drilldown
    // =========================================================================

    suspend fun previewResultEntitlements(
        roundId: String,
        winningTopTwoDigit: String,
        winningBottomTwoDigit: String
    ): ResultEntitlementPreviewOutcome {
        return commitDao.previewResultEntitlementsForRound(
            roundId = roundId,
            winningTopTwoDigit = winningTopTwoDigit,
            winningBottomTwoDigit = winningBottomTwoDigit
        )
    }

    suspend fun approveResultRevisionAtomically(
        request: ApproveResultRevisionRequest
    ): ResultApprovalOutcome {
        return commitDao.executeApproveResultRevisionTransaction(request)
    }

    suspend fun getResultRevisionsForRound(roundId: String): List<ResultRevisionEntity> {
        return commitDao.getResultRevisionsForRound(roundId)
    }

    suspend fun getActiveApprovedResultRevision(roundId: String): ResultRevisionEntity? {
        return commitDao.getActiveApprovedResultRevision(roundId)
    }

    /**
     * โหลดรายงานสรุประดับงวด ประวัติรุ่นการประกาศผล รายการบิลที่ถูกรางวัล และสรุปรายเลข 00–99
     * โดยแยกยอดสิทธิได้รับรางวัล (Winning Entitlement) ออกจากยอดเงินสดที่บันทึกจ่ายจริง (Allocated Paid Cash) อย่างชัดเจน
     */
    suspend fun getRoundReportAndDrilldownSnapshot(roundId: String): RoundReportAndDrilldownSnapshot {
        val allBills = commitDao.searchBillsInRound(roundId, "")
        val activeBills = allBills.filter { it.status == BillStatus.ACTIVE.name && it.activeRevisionId != null }
        val cancelledBills = allBills.filter { it.status == BillStatus.CANCELLED.name }

        var totalActiveReceiveMinor = 0L
        var totalActiveProvisionalPayoutMinor = 0L
        for (bill in activeBills) {
            totalActiveReceiveMinor = ExactMoneyMath.checkedAdd(
                totalActiveReceiveMinor,
                bill.currentTotalAmountMinor
            )
            totalActiveProvisionalPayoutMinor = ExactMoneyMath.checkedAdd(
                totalActiveProvisionalPayoutMinor,
                bill.currentTotalProvisionalPayoutMinor
            )
        }

        val resultRevisions = commitDao.getResultRevisionsForRound(roundId)
        val activeResultRevision = resultRevisions.firstOrNull { it.status == "APPROVED" }

        val activeEntitlements = if (activeResultRevision != null) {
            commitDao.getWinningEntitlementsForRevision(activeResultRevision.resultRevisionId)
        } else {
            emptyList()
        }

        val entitlementIds = activeEntitlements.map { it.entitlementId }
        val allocationsByEntitlementId = if (entitlementIds.isNotEmpty()) {
            commitDao.getPaymentAllocationsForTargets(
                targetType = "WINNING_ENTITLEMENT",
                targetIds = entitlementIds
            ).groupBy { it.targetId }
        } else {
            emptyMap()
        }

        var totalEntitlementPayoutMinor = 0L
        var totalAllocatedPaidCashMinor = 0L
        for (ent in activeEntitlements) {
            totalEntitlementPayoutMinor = ExactMoneyMath.checkedAdd(
                totalEntitlementPayoutMinor,
                ent.entitlementPayoutMinor
            )
            val allocatedForEnt = ExactMoneyMath.checkedSum(
                allocationsByEntitlementId[ent.entitlementId].orEmpty().map { it.allocatedAmountMinor }
            )
            totalAllocatedPaidCashMinor = ExactMoneyMath.checkedAdd(
                totalAllocatedPaidCashMinor,
                allocatedForEnt
            )
        }

        val unpaidEntitlementBalanceMinor = if (totalEntitlementPayoutMinor > totalAllocatedPaidCashMinor) {
            ExactMoneyMath.checkedSubtract(totalEntitlementPayoutMinor, totalAllocatedPaidCashMinor)
        } else {
            0L
        }

        val entitlementsByBillId = activeEntitlements.groupBy { it.billId }

        val billSummaries = activeBills.map { bill ->
            val billEnts = entitlementsByBillId[bill.billId].orEmpty()
            val billEntitlementMinor = ExactMoneyMath.checkedSum(billEnts.map { it.entitlementPayoutMinor })
            val billAllocatedPaidMinor = ExactMoneyMath.checkedSum(
                billEnts.map { ent ->
                    ExactMoneyMath.checkedSum(
                        allocationsByEntitlementId[ent.entitlementId].orEmpty().map { it.allocatedAmountMinor }
                    )
                }
            )
            val billUnpaidMinor = if (billEntitlementMinor > billAllocatedPaidMinor) {
                ExactMoneyMath.checkedSubtract(billEntitlementMinor, billAllocatedPaidMinor)
            } else {
                0L
            }

            BillReportSummaryRow(
                billId = bill.billId,
                humanBillCode = bill.humanBillCode,
                customerNoteTh = bill.customerNoteTh,
                activeRevisionId = bill.activeRevisionId ?: "",
                totalPrincipalMinor = bill.currentTotalAmountMinor,
                winningLineCount = billEnts.size,
                totalEntitlementPayoutMinor = billEntitlementMinor,
                allocatedPaidCashMinor = billAllocatedPaidMinor,
                unpaidEntitlementBalanceMinor = billUnpaidMinor
            )
        }

        val counters = commitDao.getAllCountersForRound(roundId)
            .filter { it.activeReceiveTotalMinor > 0L || it.activePayoutTotalMinor > 0L }

        val entitlementsByNumberSide = activeEntitlements.groupBy { it.numberTwoDigit to it.side }
        val numberSideRows = counters.map { counter ->
            val matchingEnts = entitlementsByNumberSide[counter.numberTwoDigit to counter.side].orEmpty()
            val numberEntitlementMinor = ExactMoneyMath.checkedSum(matchingEnts.map { it.entitlementPayoutMinor })
            val isWinningOutcome = activeResultRevision != null && (
                (counter.side == BetSide.TOP.name && counter.numberTwoDigit == activeResultRevision.winningTopTwoDigit) ||
                    (counter.side == BetSide.BOTTOM.name && counter.numberTwoDigit == activeResultRevision.winningBottomTwoDigit)
                )
            NumberSideReportRow(
                numberTwoDigit = counter.numberTwoDigit,
                side = counter.side,
                activeReceiveTotalMinor = counter.activeReceiveTotalMinor,
                activeProvisionalPayoutTotalMinor = counter.activePayoutTotalMinor,
                isWinningOutcome = isWinningOutcome,
                winningLineCount = matchingEnts.size,
                winningEntitlementTotalMinor = numberEntitlementMinor
            )
        }

        return RoundReportAndDrilldownSnapshot(
            roundId = roundId,
            activeBillCount = activeBills.size,
            cancelledBillCount = cancelledBills.size,
            totalActiveReceiveMinor = totalActiveReceiveMinor,
            totalActiveProvisionalPayoutMinor = totalActiveProvisionalPayoutMinor,
            activeResultRevision = activeResultRevision,
            allResultRevisions = resultRevisions,
            winningBillCount = billSummaries.count { it.winningLineCount > 0 },
            winningLineCount = activeEntitlements.size,
            totalEntitlementPayoutMinor = totalEntitlementPayoutMinor,
            totalAllocatedPaidCashMinor = totalAllocatedPaidCashMinor,
            unpaidEntitlementBalanceMinor = unpaidEntitlementBalanceMinor,
            billSummaries = billSummaries,
            numberSideRows = numberSideRows
        )
    }

    /**
     * เจาะลึกรายบิลและบรรทัดในบิล พร้อมแสดงสิทธิได้รับรางวัลต่อบรรทัดเทียบกับสถานะการจ่ายจริง
     */
    suspend fun getBillEntitlementDrilldown(
        roundId: String,
        billId: String
    ): BillEntitlementDrilldown? {
        val bill = commitDao.getBillById(billId) ?: return null
        if (bill.roundId != roundId) return null
        val activeRevisionId = bill.activeRevisionId ?: return null
        val activeLines = commitDao.getLinesForRevision(activeRevisionId)

        val activeResultRevision = commitDao.getActiveApprovedResultRevision(roundId)
        val billEntitlements = if (activeResultRevision != null) {
            commitDao.getWinningEntitlementsForBillInRevision(
                resultRevisionId = activeResultRevision.resultRevisionId,
                billId = billId
            )
        } else {
            emptyList()
        }

        val entitlementByLineId = billEntitlements.associateBy { it.billLineId }
        val entitlementIds = billEntitlements.map { it.entitlementId }
        val allocationsByEntitlementId = if (entitlementIds.isNotEmpty()) {
            commitDao.getPaymentAllocationsForTargets(
                targetType = "WINNING_ENTITLEMENT",
                targetIds = entitlementIds
            ).groupBy { it.targetId }
        } else {
            emptyMap()
        }

        val lineDrilldowns = activeLines.map { line ->
            val ent = entitlementByLineId[line.lineId]
            val allocatedMinor = if (ent != null) {
                ExactMoneyMath.checkedSum(
                    allocationsByEntitlementId[ent.entitlementId].orEmpty().map { it.allocatedAmountMinor }
                )
            } else {
                0L
            }
            val entPayoutMinor = ent?.entitlementPayoutMinor ?: 0L
            val unpaidMinor = if (entPayoutMinor > allocatedMinor) {
                ExactMoneyMath.checkedSubtract(entPayoutMinor, allocatedMinor)
            } else {
                0L
            }

            BillLineEntitlementDrilldownRow(
                lineId = line.lineId,
                lineNumber = line.lineNumber,
                numberTwoDigit = line.numberTwoDigit,
                side = line.side,
                principalAmountMinor = line.amountMinor,
                rateSnapshotExact = line.rateSnapshotExact,
                provisionalPayoutMinor = line.provisionalPayoutMinor,
                isWinningLine = ent != null,
                entitlementId = ent?.entitlementId,
                entitlementPayoutMinor = entPayoutMinor,
                allocatedPaidCashMinor = allocatedMinor,
                unpaidEntitlementBalanceMinor = unpaidMinor
            )
        }

        val totalEntitlementMinor = ExactMoneyMath.checkedSum(lineDrilldowns.map { it.entitlementPayoutMinor })
        val totalAllocatedPaidMinor = ExactMoneyMath.checkedSum(lineDrilldowns.map { it.allocatedPaidCashMinor })
        val totalUnpaidMinor = if (totalEntitlementMinor > totalAllocatedPaidMinor) {
            ExactMoneyMath.checkedSubtract(totalEntitlementMinor, totalAllocatedPaidMinor)
        } else {
            0L
        }

        return BillEntitlementDrilldown(
            bill = bill,
            activeRevisionId = activeRevisionId,
            activeResultRevision = activeResultRevision,
            totalEntitlementPayoutMinor = totalEntitlementMinor,
            totalAllocatedPaidCashMinor = totalAllocatedPaidMinor,
            unpaidEntitlementBalanceMinor = totalUnpaidMinor,
            lines = lineDrilldowns
        )
    }

    /**
     * เจาะลึกรายเลข 00–99 และฝั่ง (บน/ล่าง) เพื่อแสดงว่ามีบิลและบรรทัดใดบ้างที่ประกอบเป็นยอดรับและสิทธิได้รับรางวัลของเลขนั้น
     */
    suspend fun getNumberSideDrilldown(
        roundId: String,
        numberTwoDigit: String,
        side: BetSide
    ): NumberSideReportDrilldown {
        val activeBills = commitDao.searchBillsInRound(roundId, "")
            .filter { it.status == BillStatus.ACTIVE.name && it.activeRevisionId != null }
        val activeResultRevision = commitDao.getActiveApprovedResultRevision(roundId)
        val activeEntitlementsByLineId = if (activeResultRevision != null) {
            commitDao.getWinningEntitlementsForRevision(activeResultRevision.resultRevisionId)
                .associateBy { it.billLineId }
        } else {
            emptyMap()
        }

        val contributingLines = mutableListOf<NumberSideContributingBillLineRow>()
        var totalPrincipalMinor = 0L
        var totalProvisionalPayoutMinor = 0L
        var totalEntitlementMinor = 0L

        for (bill in activeBills) {
            val revId = bill.activeRevisionId ?: continue
            val lines = commitDao.getLinesForRevision(revId)
            for (line in lines) {
                if (line.numberTwoDigit == numberTwoDigit && line.side == side.name) {
                    val ent = activeEntitlementsByLineId[line.lineId]
                    val entMinor = ent?.entitlementPayoutMinor ?: 0L
                    totalPrincipalMinor = ExactMoneyMath.checkedAdd(totalPrincipalMinor, line.amountMinor)
                    totalProvisionalPayoutMinor = ExactMoneyMath.checkedAdd(
                        totalProvisionalPayoutMinor,
                        line.provisionalPayoutMinor
                    )
                    totalEntitlementMinor = ExactMoneyMath.checkedAdd(totalEntitlementMinor, entMinor)

                    contributingLines.add(
                        NumberSideContributingBillLineRow(
                            billId = bill.billId,
                            humanBillCode = bill.humanBillCode,
                            customerNoteTh = bill.customerNoteTh,
                            lineNumber = line.lineNumber,
                            principalAmountMinor = line.amountMinor,
                            rateSnapshotExact = line.rateSnapshotExact,
                            provisionalPayoutMinor = line.provisionalPayoutMinor,
                            entitlementPayoutMinor = entMinor,
                            isWinningEntitlement = ent != null
                        )
                    )
                }
            }
        }

        return NumberSideReportDrilldown(
            roundId = roundId,
            numberTwoDigit = numberTwoDigit,
            side = side,
            totalPrincipalMinor = totalPrincipalMinor,
            totalProvisionalPayoutMinor = totalProvisionalPayoutMinor,
            totalEntitlementPayoutMinor = totalEntitlementMinor,
            contributingLines = contributingLines
        )
    }
}

data class BillWithHistorySnapshot(
    val bill: BillEntity,
    val revisions: List<BillRevisionEntity>,
    val activeLines: List<BillNumberSideLineEntity>
)

data class BillReportSummaryRow(
    val billId: String,
    val humanBillCode: String,
    val customerNoteTh: String,
    val activeRevisionId: String,
    val totalPrincipalMinor: Long,
    val winningLineCount: Int,
    val totalEntitlementPayoutMinor: Long,
    val allocatedPaidCashMinor: Long,
    val unpaidEntitlementBalanceMinor: Long
)

data class NumberSideReportRow(
    val numberTwoDigit: String,
    val side: String,
    val activeReceiveTotalMinor: Long,
    val activeProvisionalPayoutTotalMinor: Long,
    val isWinningOutcome: Boolean,
    val winningLineCount: Int,
    val winningEntitlementTotalMinor: Long
)

data class RoundReportAndDrilldownSnapshot(
    val roundId: String,
    val activeBillCount: Int,
    val cancelledBillCount: Int,
    val totalActiveReceiveMinor: Long,
    val totalActiveProvisionalPayoutMinor: Long,
    val activeResultRevision: ResultRevisionEntity?,
    val allResultRevisions: List<ResultRevisionEntity>,
    val winningBillCount: Int,
    val winningLineCount: Int,
    val totalEntitlementPayoutMinor: Long,
    val totalAllocatedPaidCashMinor: Long,
    val unpaidEntitlementBalanceMinor: Long,
    val billSummaries: List<BillReportSummaryRow>,
    val numberSideRows: List<NumberSideReportRow>
)

data class BillLineEntitlementDrilldownRow(
    val lineId: Long,
    val lineNumber: Int,
    val numberTwoDigit: String,
    val side: String,
    val principalAmountMinor: Long,
    val rateSnapshotExact: String,
    val provisionalPayoutMinor: Long,
    val isWinningLine: Boolean,
    val entitlementId: String?,
    val entitlementPayoutMinor: Long,
    val allocatedPaidCashMinor: Long,
    val unpaidEntitlementBalanceMinor: Long
)

data class BillEntitlementDrilldown(
    val bill: BillEntity,
    val activeRevisionId: String,
    val activeResultRevision: ResultRevisionEntity?,
    val totalEntitlementPayoutMinor: Long,
    val totalAllocatedPaidCashMinor: Long,
    val unpaidEntitlementBalanceMinor: Long,
    val lines: List<BillLineEntitlementDrilldownRow>
)

data class NumberSideContributingBillLineRow(
    val billId: String,
    val humanBillCode: String,
    val customerNoteTh: String,
    val lineNumber: Int,
    val principalAmountMinor: Long,
    val rateSnapshotExact: String,
    val provisionalPayoutMinor: Long,
    val entitlementPayoutMinor: Long,
    val isWinningEntitlement: Boolean
)

data class NumberSideReportDrilldown(
    val roundId: String,
    val numberTwoDigit: String,
    val side: BetSide,
    val totalPrincipalMinor: Long,
    val totalProvisionalPayoutMinor: Long,
    val totalEntitlementPayoutMinor: Long,
    val contributingLines: List<NumberSideContributingBillLineRow>
)
