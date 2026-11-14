package com.oneproject.offline.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.oneproject.offline.core.money.ExactMoneyMath
import com.oneproject.offline.data.local.entity.ApprovalRecordEntity
import com.oneproject.offline.data.local.entity.AuditLogEntity
import com.oneproject.offline.data.local.entity.BetSide
import com.oneproject.offline.data.local.entity.BillEntity
import com.oneproject.offline.data.local.entity.BillNumberSideLineEntity
import com.oneproject.offline.data.local.entity.BillRevisionEntity
import com.oneproject.offline.data.local.entity.BillStatus
import com.oneproject.offline.data.local.entity.DurableOperationEntity
import com.oneproject.offline.data.local.entity.DurableOperationStatus
import com.oneproject.offline.data.local.entity.LocalActorEntity
import com.oneproject.offline.data.local.entity.NumberPolicyOverrideEntity
import com.oneproject.offline.data.local.entity.NumberSideCounterEntity
import com.oneproject.offline.data.local.entity.PaymentAllocationEntity
import com.oneproject.offline.data.local.entity.PolicyVersionEntity
import com.oneproject.offline.data.local.entity.QuoteSnapshotEntity
import com.oneproject.offline.data.local.entity.ResultRevisionEntity
import com.oneproject.offline.data.local.entity.ResultRevisionStatus
import com.oneproject.offline.data.local.entity.RoundEntity
import com.oneproject.offline.data.local.entity.RoundStatus
import com.oneproject.offline.data.local.entity.WinningEntitlementEntity
import com.oneproject.offline.domain.engine.NumberExpansionEngine

/**
 * DAO หลักสำหรับธุรกรรมที่มีผลต่อวงเงิน บิล ประวัติการแก้ไข นโยบาย การจัดการสถานะงวด และการประกาศผลรางวัล
 */
@Dao
interface AuthoritativeCommitDao {

    @Query("SELECT * FROM local_actors WHERE actor_id = :actorId LIMIT 1")
    suspend fun getLocalActorById(actorId: String): LocalActorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocalActor(actor: LocalActorEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertApprovalRecord(approval: ApprovalRecordEntity)

    @Query("SELECT * FROM approval_records WHERE round_id = :roundId ORDER BY approved_at_epoch_ms DESC")
    suspend fun getApprovalsForRound(roundId: String): List<ApprovalRecordEntity>

    @Query("SELECT * FROM durable_operations WHERE operation_key = :operationKey LIMIT 1")
    suspend fun getDurableOperation(operationKey: String): DurableOperationEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDurableOperation(operation: DurableOperationEntity)

    @Query("SELECT * FROM rounds WHERE round_id = :roundId LIMIT 1")
    suspend fun getRoundById(roundId: String): RoundEntity?

    @Query("SELECT * FROM rounds ORDER BY created_at_epoch_ms DESC")
    suspend fun getAllRounds(): List<RoundEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRound(round: RoundEntity)

    @Update
    suspend fun updateRound(round: RoundEntity)

    @Query(
        """
        SELECT * FROM policy_versions
        WHERE round_id = :roundId AND policy_version = :policyVersion
        LIMIT 1
        """
    )
    suspend fun getPolicyVersion(roundId: String, policyVersion: Long): PolicyVersionEntity?

    @Query("SELECT * FROM policy_versions WHERE round_id = :roundId ORDER BY policy_version DESC")
    suspend fun getPolicyHistoryForRound(roundId: String): List<PolicyVersionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPolicyVersion(policyVersion: PolicyVersionEntity): Long

    @Query(
        """
        SELECT * FROM number_policy_overrides
        WHERE round_id = :roundId
          AND policy_version = :policyVersion
          AND number_two_digit = :numberTwoDigit
          AND side = :side
        LIMIT 1
        """
    )
    suspend fun getNumberPolicyOverride(
        roundId: String,
        policyVersion: Long,
        numberTwoDigit: String,
        side: String
    ): NumberPolicyOverrideEntity?

    @Query(
        """
        SELECT * FROM number_policy_overrides
        WHERE round_id = :roundId AND policy_version = :policyVersion
        ORDER BY number_two_digit ASC, side ASC
        """
    )
    suspend fun getOverridesForPolicyVersion(
        roundId: String,
        policyVersion: Long
    ): List<NumberPolicyOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNumberPolicyOverride(overrideEntity: NumberPolicyOverrideEntity)

    @Query("SELECT * FROM quote_snapshots WHERE quote_id = :quoteId LIMIT 1")
    suspend fun getQuoteSnapshot(quoteId: String): QuoteSnapshotEntity?

    @Query(
        """
        SELECT * FROM number_side_counters
        WHERE round_id = :roundId
          AND number_two_digit = :numberTwoDigit
          AND side = :side
        LIMIT 1
        """
    )
    suspend fun getNumberSideCounter(
        roundId: String,
        numberTwoDigit: String,
        side: String
    ): NumberSideCounterEntity?

    @Query("SELECT * FROM number_side_counters WHERE round_id = :roundId ORDER BY number_two_digit ASC, side ASC")
    suspend fun getAllCountersForRound(roundId: String): List<NumberSideCounterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNumberSideCounter(counter: NumberSideCounterEntity)

    @Query("SELECT * FROM bills WHERE bill_id = :billId LIMIT 1")
    suspend fun getBillById(billId: String): BillEntity?

    @Query("SELECT * FROM bills WHERE human_bill_code = :humanBillCode LIMIT 1")
    suspend fun getBillByHumanCode(humanBillCode: String): BillEntity?

    @Query(
        """
        SELECT * FROM bills
        WHERE round_id = :roundId
          AND (:searchQuery = ''
               OR human_bill_code LIKE '%' || :searchQuery || '%'
               OR bill_id LIKE '%' || :searchQuery || '%'
               OR customer_note_th LIKE '%' || :searchQuery || '%')
        ORDER BY created_at_epoch_ms DESC
        """
    )
    suspend fun searchBillsInRound(roundId: String, searchQuery: String): List<BillEntity>

    @Query("SELECT COUNT(*) FROM bills WHERE round_id = :roundId")
    suspend fun countBillsInRound(roundId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBill(bill: BillEntity)

    @Update
    suspend fun updateBill(bill: BillEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBillRevision(revision: BillRevisionEntity)

    @Query("SELECT * FROM bill_revisions WHERE revision_id = :revisionId LIMIT 1")
    suspend fun getBillRevisionById(revisionId: String): BillRevisionEntity?

    @Query("SELECT * FROM bill_revisions WHERE bill_id = :billId ORDER BY revision_number ASC")
    suspend fun getRevisionsForBill(billId: String): List<BillRevisionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBillLines(lines: List<BillNumberSideLineEntity>)

    @Query("SELECT * FROM bill_number_side_lines WHERE revision_id = :revisionId ORDER BY line_number ASC")
    suspend fun getLinesForRevision(revisionId: String): List<BillNumberSideLineEntity>

    @Query("DELETE FROM cart_lines WHERE cart_id = :cartId")
    suspend fun clearCartLinesById(cartId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAuditLog(audit: AuditLogEntity): Long

    /**
     * ยืนยันบิลใหม่แบบ Atomic ภายใน Room Transaction เดียวกัน
     */
    @Transaction
    suspend fun executeAtomicBillCommit(request: AtomicBillCommitRequest): AtomicCommitResult {
        if (request.actorId.isBlank()) {
            return AtomicCommitResult.Rejected(
                code = "LOCAL_ACCESS_DENIED",
                messageTh = "ไม่พบรหัสผู้ใช้งานในเครื่อง"
            )
        }
        val actor = getLocalActorById(request.actorId)
        if (actor != null && !actor.isActive) {
            return AtomicCommitResult.Rejected(
                code = "LOCAL_ACTOR_INACTIVE",
                messageTh = "บัญชีผู้ใช้งานในเครื่องถูกระงับการใช้งาน"
            )
        }

        // ตรวจสอบ operation_key ก่อนตรวจอายุ Quote (Replay completed operation key before stale quote checks)
        val existingOp = getDurableOperation(request.operationKey)
        if (existingOp != null) {
            if (existingOp.payloadSha256 != request.payloadSha256) {
                return AtomicCommitResult.PayloadConflict(
                    operationKey = request.operationKey,
                    existingPayloadSha256 = existingOp.payloadSha256,
                    incomingPayloadSha256 = request.payloadSha256,
                    messageTh = "พบการใช้ operationKey ซ้ำแต่ข้อมูลรายการ (Payload) ไม่ตรงกับครั้งก่อน"
                )
            }
            if (existingOp.status == DurableOperationStatus.COMPLETED.name &&
                existingOp.committedBillId != null &&
                existingOp.committedRevisionId != null &&
                existingOp.humanBillCode != null
            ) {
                return AtomicCommitResult.Committed(
                    billId = existingOp.committedBillId,
                    revisionId = existingOp.committedRevisionId,
                    humanBillCode = existingOp.humanBillCode,
                    revisionNumber = 1,
                    replayedFromDurableOperation = true
                )
            }
        }

        val round = getRoundById(request.roundId)
            ?: return AtomicCommitResult.Rejected(
                code = "ROUND_NOT_FOUND",
                messageTh = "ไม่พบงวดที่ต้องการบันทึกในเครื่อง"
            )

        if (round.status != RoundStatus.OPEN.name) {
            return AtomicCommitResult.Rejected(
                code = "ROUND_NOT_OPEN",
                messageTh = "งวดปัจจุบันไม่ได้เปิดรับรายการ (สถานะ: ${round.status})"
            )
        }

        if (round.activePolicyVersion != request.expectedPolicyVersion) {
            return AtomicCommitResult.Rejected(
                code = "POLICY_CHANGED_FORCE_REVIEW",
                messageTh = "นโยบายหรืออัตราจ่ายมีการเปลี่ยนแปลง (รุ่น #${request.expectedPolicyVersion} -> #${round.activePolicyVersion}) กรุณาตรวจทานรายการใหม่อีกครั้ง"
            )
        }

        val quote = getQuoteSnapshot(request.quoteId)
            ?: return AtomicCommitResult.Rejected(
                code = "QUOTE_NOT_FOUND",
                messageTh = "ไม่พบข้อมูลใบตรวจทาน (Quote) ในเครื่อง"
            )

        if (quote.payloadSha256 != request.payloadSha256 ||
            quote.roundId != request.roundId ||
            quote.policyVersion != request.expectedPolicyVersion
        ) {
            return AtomicCommitResult.Rejected(
                code = "QUOTE_BINDING_MISMATCH",
                messageTh = "ข้อมูลใบตรวจทานไม่ตรงกับรายการหรือรุ่นนโยบายปัจจุบัน"
            )
        }

        if (request.nowEpochMs > quote.expiresAtEpochMs) {
            return AtomicCommitResult.Rejected(
                code = "QUOTE_STALE_EXPIRED",
                messageTh = "ใบตรวจทานหมดอายุ กรุณากดตรวจทานรายการใหม่"
            )
        }

        val policy = getPolicyVersion(request.roundId, request.expectedPolicyVersion)
            ?: return AtomicCommitResult.Rejected(
                code = "POLICY_VERSION_NOT_FOUND",
                messageTh = "ไม่พบรุ่นนโยบาย #${request.expectedPolicyVersion} ในฐานข้อมูล"
            )

        if (request.lines.isEmpty()) {
            return AtomicCommitResult.Rejected(
                code = "EMPTY_LINES",
                messageTh = "ไม่สามารถยืนยันบิลที่ไม่มีรายการเลขได้"
            )
        }

        val groupedLines = request.lines.groupBy { it.numberTwoDigit to it.side }
        val updatedCounters = mutableListOf<NumberSideCounterEntity>()

        var totalBillAmountMinor = 0L
        var totalBillPayoutMinor = 0L

        for ((key, linesForKey) in groupedLines) {
            val (numberTwoDigit, side) = key
            val override = getNumberPolicyOverride(
                roundId = request.roundId,
                policyVersion = request.expectedPolicyVersion,
                numberTwoDigit = numberTwoDigit,
                side = side
            )

            if (override?.overrideIsClosed == true) {
                return AtomicCommitResult.Rejected(
                    code = "NUMBER_CLOSED_FOR_ORDER",
                    messageTh = "เลข $numberTwoDigit ($side) ปิดรับรายการแล้ว"
                )
            }

            val effectiveReceiveCap = override?.overrideReceiveCapMinor ?: policy.defaultReceiveCapMinor
            val effectivePayoutCap = override?.overridePayoutCapMinor ?: policy.defaultPayoutCapMinor

            var deltaReceiveMinor = 0L
            var deltaPayoutMinor = 0L

            for (line in linesForKey) {
                if (line.amountMinor <= 0L || line.provisionalPayoutMinor < 0L) {
                    return AtomicCommitResult.Rejected(
                        code = "INVALID_LINE_AMOUNT",
                        messageTh = "ยอดเงินในรายการต้องมากกว่า 0 สตางค์"
                    )
                }
                try {
                    deltaReceiveMinor = Math.addExact(deltaReceiveMinor, line.amountMinor)
                    deltaPayoutMinor = Math.addExact(deltaPayoutMinor, line.provisionalPayoutMinor)
                    totalBillAmountMinor = Math.addExact(totalBillAmountMinor, line.amountMinor)
                    totalBillPayoutMinor = Math.addExact(totalBillPayoutMinor, line.provisionalPayoutMinor)
                } catch (overflow: ArithmeticException) {
                    return AtomicCommitResult.Rejected(
                        code = "ARITHMETIC_OVERFLOW",
                        messageTh = "ยอดรวมเกินขอบเขตตัวเลข 64-bit (Long Overflow)"
                    )
                }
            }

            val existingCounter = getNumberSideCounter(
                roundId = request.roundId,
                numberTwoDigit = numberTwoDigit,
                side = side
            )

            val currentReceive = existingCounter?.activeReceiveTotalMinor ?: 0L
            val currentPayout = existingCounter?.activePayoutTotalMinor ?: 0L
            val currentCount = existingCounter?.activeLineCount ?: 0

            val newReceiveTotal = try {
                Math.addExact(currentReceive, deltaReceiveMinor)
            } catch (overflow: ArithmeticException) {
                return AtomicCommitResult.Rejected(
                    code = "COUNTER_RECEIVE_OVERFLOW",
                    messageTh = "ยอดสะสมรับของเลข $numberTwoDigit ($side) เกินค่าสูงสุดที่ระบบรองรับ"
                )
            }

            val newPayoutTotal = try {
                Math.addExact(currentPayout, deltaPayoutMinor)
            } catch (overflow: ArithmeticException) {
                return AtomicCommitResult.Rejected(
                    code = "COUNTER_PAYOUT_OVERFLOW",
                    messageTh = "ยอดสะสมจ่ายของเลข $numberTwoDigit ($side) เกินค่าสูงสุดที่ระบบรองรับ"
                )
            }

            if (newReceiveTotal > effectiveReceiveCap) {
                return AtomicCommitResult.Rejected(
                    code = "RECEIVE_CAP_EXCEEDED",
                    messageTh = "เลข $numberTwoDigit ($side) เกินเพดานรับ (ยอดรวมใหม่ $newReceiveTotal > เพดาน $effectiveReceiveCap สตางค์)"
                )
            }

            if (newPayoutTotal > effectivePayoutCap) {
                return AtomicCommitResult.Rejected(
                    code = "PAYOUT_CAP_EXCEEDED",
                    messageTh = "เลข $numberTwoDigit ($side) เกินเพดานจ่ายสูงสุด (ยอดจ่ายรวมใหม่ $newPayoutTotal > เพดาน $effectivePayoutCap สตางค์)"
                )
            }

            updatedCounters.add(
                NumberSideCounterEntity(
                    roundId = request.roundId,
                    numberTwoDigit = numberTwoDigit,
                    side = side,
                    activeReceiveTotalMinor = newReceiveTotal,
                    activePayoutTotalMinor = newPayoutTotal,
                    activeLineCount = currentCount + linesForKey.size,
                    updatedAtEpochMs = request.nowEpochMs
                )
            )
        }

        val sequenceInRound = countBillsInRound(request.roundId) + 1
        val humanBillCode = request.explicitHumanBillCode
            ?: "${round.roundCode}-B%05d".format(sequenceInRound)

        val billEntity = BillEntity(
            billId = request.newBillId,
            humanBillCode = humanBillCode,
            roundId = request.roundId,
            activeRevisionId = request.newRevisionId,
            latestRevisionNumber = 1,
            status = BillStatus.ACTIVE.name,
            customerNoteTh = request.customerNoteTh,
            cancelReasonTh = null,
            cancelledAtEpochMs = null,
            createdAtEpochMs = request.nowEpochMs,
            updatedAtEpochMs = request.nowEpochMs
        )

        val distinctNumbers = request.lines.map { it.numberTwoDigit }.distinct().size
        val revisionEntity = BillRevisionEntity(
            revisionId = request.newRevisionId,
            billId = request.newBillId,
            revisionNumber = 1,
            roundId = request.roundId,
            policyVersion = request.expectedPolicyVersion,
            operationKey = request.operationKey,
            totalAmountMinor = totalBillAmountMinor,
            totalProvisionalPayoutMinor = totalBillPayoutMinor,
            lineCount = request.lines.size,
            distinctNumberCount = distinctNumbers,
            revisionReasonTh = "ยืนยันบิลใหม่ในเครื่อง",
            createdByActorId = request.actorId,
            createdAtEpochMs = request.nowEpochMs
        )

        val persistedLines = request.lines.mapIndexed { idx, line ->
            BillNumberSideLineEntity(
                revisionId = request.newRevisionId,
                billId = request.newBillId,
                roundId = request.roundId,
                lineNumber = idx + 1,
                numberTwoDigit = line.numberTwoDigit,
                side = line.side,
                amountMinor = line.amountMinor,
                rateSnapshotExact = line.rateSnapshotExact,
                provisionalPayoutMinor = line.provisionalPayoutMinor,
                roundingMode = line.roundingMode
            )
        }

        insertBill(billEntity)
        insertBillRevision(revisionEntity)
        insertBillLines(persistedLines)

        updatedCounters.forEach { counter ->
            upsertNumberSideCounter(counter)
        }

        if (request.cartIdToClear != null) {
            clearCartLinesById(request.cartIdToClear)
        }

        insertDurableOperation(
            DurableOperationEntity(
                operationKey = request.operationKey,
                operationType = "COMMIT_NEW_BILL",
                roundId = request.roundId,
                policyVersion = request.expectedPolicyVersion,
                quoteId = request.quoteId,
                payloadSha256 = request.payloadSha256,
                status = DurableOperationStatus.COMPLETED.name,
                committedBillId = request.newBillId,
                committedRevisionId = request.newRevisionId,
                humanBillCode = humanBillCode,
                rejectionCode = null,
                rejectionMessageTh = null,
                createdAtEpochMs = request.nowEpochMs,
                completedAtEpochMs = request.nowEpochMs
            )
        )

        insertAuditLog(
            AuditLogEntity(
                eventType = "BILL_COMMITTED",
                actorRole = request.actorId,
                roundId = request.roundId,
                summaryTh = "ยืนยันบิล $humanBillCode (Revision #1) ยอดรวม $totalBillAmountMinor สตางค์ จำนวน ${persistedLines.size} รายการ",
                createdAtEpochMs = request.nowEpochMs
            )
        )

        return AtomicCommitResult.Committed(
            billId = request.newBillId,
            revisionId = request.newRevisionId,
            humanBillCode = humanBillCode,
            revisionNumber = 1,
            replayedFromDurableOperation = false
        )
    }

    /**
     * แก้ไขบิลโดยสร้าง Revision ใหม่และตัดยอด Revision เดิมที่เคย Active ออกจากตัวนับทันที
     * กฎข้อ 01 & 04_SPEC: Edit total = otherActiveBills + replacement (NOT old + replacement)
     */
    @Transaction
    suspend fun executeAtomicBillEditRevision(request: AtomicBillEditRequest): AtomicCommitResult {
        val existingOp = getDurableOperation(request.operationKey)
        if (existingOp != null) {
            if (existingOp.payloadSha256 != request.payloadSha256) {
                return AtomicCommitResult.PayloadConflict(
                    operationKey = request.operationKey,
                    existingPayloadSha256 = existingOp.payloadSha256,
                    incomingPayloadSha256 = request.payloadSha256,
                    messageTh = "พบการใช้ operationKey ซ้ำในการแก้ไขบิลแต่ข้อมูล Payload ไม่ตรงกัน"
                )
            }
            if (existingOp.status == DurableOperationStatus.COMPLETED.name &&
                existingOp.committedBillId != null &&
                existingOp.committedRevisionId != null &&
                existingOp.humanBillCode != null
            ) {
                val rev = getBillRevisionById(existingOp.committedRevisionId)
                return AtomicCommitResult.Committed(
                    billId = existingOp.committedBillId,
                    revisionId = existingOp.committedRevisionId,
                    humanBillCode = existingOp.humanBillCode,
                    revisionNumber = rev?.revisionNumber ?: 2,
                    replayedFromDurableOperation = true
                )
            }
        }

        if (request.revisionReasonTh.isBlank()) {
            return AtomicCommitResult.Rejected(
                code = "MISSING_REVISION_REASON",
                messageTh = "ต้องระบุเหตุผลในการแก้ไขบิล"
            )
        }

        val bill = getBillById(request.billId)
            ?: return AtomicCommitResult.Rejected(
                code = "BILL_NOT_FOUND",
                messageTh = "ไม่พบบิลที่ต้องการแก้ไข"
            )

        if (bill.status != BillStatus.ACTIVE.name || bill.activeRevisionId == null) {
            return AtomicCommitResult.Rejected(
                code = "BILL_NOT_ACTIVE",
                messageTh = "ไม่สามารถแก้ไขบิลที่ถูกยกเลิกไปแล้วได้"
            )
        }

        val round = getRoundById(bill.roundId)
            ?: return AtomicCommitResult.Rejected(
                code = "ROUND_NOT_FOUND",
                messageTh = "ไม่พบงวดของบิลนี้"
            )

        if (round.status != RoundStatus.OPEN.name) {
            return AtomicCommitResult.Rejected(
                code = "ROUND_NOT_OPEN",
                messageTh = "ไม่สามารถแก้ไขบิลปกติได้เมื่องวดไม่ได้อยู่ในสถานะ OPEN (งวดที่ปิดแล้วต้องใช้รายการปรับปรุงที่ได้รับอนุมัติ)"
            )
        }

        if (round.activePolicyVersion != request.expectedPolicyVersion) {
            return AtomicCommitResult.Rejected(
                code = "POLICY_CHANGED_FORCE_REVIEW",
                messageTh = "นโยบายเปลี่ยนเป็นรุ่น #${round.activePolicyVersion} กรุณาตรวจทานการแก้ไขใหม่"
            )
        }

        val policy = getPolicyVersion(round.roundId, request.expectedPolicyVersion)
            ?: return AtomicCommitResult.Rejected(
                code = "POLICY_VERSION_NOT_FOUND",
                messageTh = "ไม่พบรุ่นนโยบาย #${request.expectedPolicyVersion}"
            )

        val oldActiveLines = getLinesForRevision(bill.activeRevisionId)
        val oldGrouped = oldActiveLines.groupBy { it.numberTwoDigit to it.side }
        val newGrouped = request.replacementLines.groupBy { it.numberTwoDigit to it.side }
        val allAffectedKeys = (oldGrouped.keys + newGrouped.keys).distinct()

        val updatedCounters = mutableListOf<NumberSideCounterEntity>()

        for (key in allAffectedKeys) {
            val (numberTwoDigit, side) = key
            val existingCounter = getNumberSideCounter(round.roundId, numberTwoDigit, side)
            val currentReceive = existingCounter?.activeReceiveTotalMinor ?: 0L
            val currentPayout = existingCounter?.activePayoutTotalMinor ?: 0L
            val currentCount = existingCounter?.activeLineCount ?: 0

            val oldLinesForKey = oldGrouped[key].orEmpty()
            var oldReceiveForBill = 0L
            var oldPayoutForBill = 0L
            for (oldLine in oldLinesForKey) {
                oldReceiveForBill = Math.addExact(oldReceiveForBill, oldLine.amountMinor)
                oldPayoutForBill = Math.addExact(oldPayoutForBill, oldLine.provisionalPayoutMinor)
            }

            // ตัดยอด Revision เดิมออกก่อน: otherActiveBills = currentCounter - oldRevisionContribution
            val otherActiveReceive = Math.subtractExact(currentReceive, oldReceiveForBill).coerceAtLeast(0L)
            val otherActivePayout = Math.subtractExact(currentPayout, oldPayoutForBill).coerceAtLeast(0L)
            val otherActiveCount = (currentCount - oldLinesForKey.size).coerceAtLeast(0)

            val replacementLinesForKey = newGrouped[key].orEmpty()
            var replacementReceive = 0L
            var replacementPayout = 0L
            for (repLine in replacementLinesForKey) {
                if (repLine.amountMinor <= 0L || repLine.provisionalPayoutMinor < 0L) {
                    return AtomicCommitResult.Rejected(
                        code = "INVALID_LINE_AMOUNT",
                        messageTh = "ยอดเงินในรายการแก้ไขต้องมากกว่า 0 สตางค์"
                    )
                }
                try {
                    replacementReceive = Math.addExact(replacementReceive, repLine.amountMinor)
                    replacementPayout = Math.addExact(replacementPayout, repLine.provisionalPayoutMinor)
                } catch (overflow: ArithmeticException) {
                    return AtomicCommitResult.Rejected(
                        code = "ARITHMETIC_OVERFLOW",
                        messageTh = "ยอดรวมในรายการแก้ไขเกินขอบเขต Long"
                    )
                }
            }

            val newTotalReceive = try {
                Math.addExact(otherActiveReceive, replacementReceive)
            } catch (overflow: ArithmeticException) {
                return AtomicCommitResult.Rejected(
                    code = "COUNTER_RECEIVE_OVERFLOW",
                    messageTh = "ยอดสะสมรับหลังแก้ไขเกินขอบเขต Long"
                )
            }

            val newTotalPayout = try {
                Math.addExact(otherActivePayout, replacementPayout)
            } catch (overflow: ArithmeticException) {
                return AtomicCommitResult.Rejected(
                    code = "COUNTER_PAYOUT_OVERFLOW",
                    messageTh = "ยอดสะสมจ่ายหลังแก้ไขเกินขอบเขต Long"
                )
            }

            if (replacementLinesForKey.isNotEmpty()) {
                val override = getNumberPolicyOverride(
                    roundId = round.roundId,
                    policyVersion = request.expectedPolicyVersion,
                    numberTwoDigit = numberTwoDigit,
                    side = side
                )
                if (override?.overrideIsClosed == true) {
                    return AtomicCommitResult.Rejected(
                        code = "NUMBER_CLOSED_FOR_ORDER",
                        messageTh = "เลข $numberTwoDigit ($side) ปิดรับรายการแล้ว"
                    )
                }
                val effectiveReceiveCap = override?.overrideReceiveCapMinor ?: policy.defaultReceiveCapMinor
                val effectivePayoutCap = override?.overridePayoutCapMinor ?: policy.defaultPayoutCapMinor

                if (newTotalReceive > effectiveReceiveCap) {
                    return AtomicCommitResult.Rejected(
                        code = "RECEIVE_CAP_EXCEEDED",
                        messageTh = "การแก้ไขทำให้เลข $numberTwoDigit ($side) เกินเพดานรับ (บิลอื่น $otherActiveReceive + ยอดใหม่ $replacementReceive = $newTotalReceive > เพดาน $effectiveReceiveCap สตางค์)"
                    )
                }
                if (newTotalPayout > effectivePayoutCap) {
                    return AtomicCommitResult.Rejected(
                        code = "PAYOUT_CAP_EXCEEDED",
                        messageTh = "การแก้ไขทำให้เลข $numberTwoDigit ($side) เกินเพดานจ่าย ($newTotalPayout > เพดาน $effectivePayoutCap สตางค์)"
                    )
                }
            }

            updatedCounters.add(
                NumberSideCounterEntity(
                    roundId = round.roundId,
                    numberTwoDigit = numberTwoDigit,
                    side = side,
                    activeReceiveTotalMinor = newTotalReceive,
                    activePayoutTotalMinor = newTotalPayout,
                    activeLineCount = otherActiveCount + replacementLinesForKey.size,
                    updatedAtEpochMs = request.nowEpochMs
                )
            )
        }

        var totalNewBillMinor = 0L
        var totalNewPayoutMinor = 0L
        for (line in request.replacementLines) {
            totalNewBillMinor = Math.addExact(totalNewBillMinor, line.amountMinor)
            totalNewPayoutMinor = Math.addExact(totalNewPayoutMinor, line.provisionalPayoutMinor)
        }

        val nextRevisionNumber = bill.latestRevisionNumber + 1
        val newRevision = BillRevisionEntity(
            revisionId = request.newRevisionId,
            billId = bill.billId,
            revisionNumber = nextRevisionNumber,
            roundId = round.roundId,
            policyVersion = request.expectedPolicyVersion,
            operationKey = request.operationKey,
            totalAmountMinor = totalNewBillMinor,
            totalProvisionalPayoutMinor = totalNewPayoutMinor,
            lineCount = request.replacementLines.size,
            distinctNumberCount = request.replacementLines.map { it.numberTwoDigit }.distinct().size,
            revisionReasonTh = request.revisionReasonTh,
            createdByActorId = request.actorId,
            createdAtEpochMs = request.nowEpochMs
        )

        val persistedReplacementLines = request.replacementLines.mapIndexed { idx, line ->
            BillNumberSideLineEntity(
                revisionId = request.newRevisionId,
                billId = bill.billId,
                roundId = round.roundId,
                lineNumber = idx + 1,
                numberTwoDigit = line.numberTwoDigit,
                side = line.side,
                amountMinor = line.amountMinor,
                rateSnapshotExact = line.rateSnapshotExact,
                provisionalPayoutMinor = line.provisionalPayoutMinor,
                roundingMode = line.roundingMode
            )
        }

        insertBillRevision(newRevision)
        insertBillLines(persistedReplacementLines)
        updateBill(
            bill.copy(
                activeRevisionId = request.newRevisionId,
                latestRevisionNumber = nextRevisionNumber,
                updatedAtEpochMs = request.nowEpochMs
            )
        )

        updatedCounters.forEach { counter ->
            upsertNumberSideCounter(counter)
        }

        insertDurableOperation(
            DurableOperationEntity(
                operationKey = request.operationKey,
                operationType = "EDIT_BILL_REVISION",
                roundId = round.roundId,
                policyVersion = request.expectedPolicyVersion,
                quoteId = null,
                payloadSha256 = request.payloadSha256,
                status = DurableOperationStatus.COMPLETED.name,
                committedBillId = bill.billId,
                committedRevisionId = request.newRevisionId,
                humanBillCode = bill.humanBillCode,
                rejectionCode = null,
                rejectionMessageTh = null,
                createdAtEpochMs = request.nowEpochMs,
                completedAtEpochMs = request.nowEpochMs
            )
        )

        insertAuditLog(
            AuditLogEntity(
                eventType = "BILL_REVISION_COMMITTED",
                actorRole = request.actorId,
                roundId = round.roundId,
                summaryTh = "แก้ไขบิล ${bill.humanBillCode} เป็น Revision #$nextRevisionNumber เหตุผล: ${request.revisionReasonTh}",
                createdAtEpochMs = request.nowEpochMs
            )
        )

        return AtomicCommitResult.Committed(
            billId = bill.billId,
            revisionId = request.newRevisionId,
            humanBillCode = bill.humanBillCode,
            revisionNumber = nextRevisionNumber,
            replayedFromDurableOperation = false
        )
    }

    /**
     * ยกเลิกบิลพร้อมเหตุผล (ห้ามลบข้อมูลจริง แต่ถอนยอดออกจาก Active Counter และเก็บประวัติ Revision ทั้งหมดไว้)
     */
    @Transaction
    suspend fun executeAtomicBillCancel(
        operationKey: String,
        payloadSha256: String,
        billId: String,
        cancelReasonTh: String,
        actorId: String,
        nowEpochMs: Long
    ): AtomicCommitResult {
        val existingOp = getDurableOperation(operationKey)
        if (existingOp != null) {
            if (existingOp.payloadSha256 != payloadSha256) {
                return AtomicCommitResult.PayloadConflict(
                    operationKey = operationKey,
                    existingPayloadSha256 = existingOp.payloadSha256,
                    incomingPayloadSha256 = payloadSha256,
                    messageTh = "operationKey ซ้ำแต่ข้อมูลคำขอยกเลิกบิลไม่ตรงกัน"
                )
            }
            if (existingOp.status == DurableOperationStatus.COMPLETED.name &&
                existingOp.committedBillId != null &&
                existingOp.humanBillCode != null
            ) {
                return AtomicCommitResult.Committed(
                    billId = existingOp.committedBillId,
                    revisionId = existingOp.committedRevisionId ?: "",
                    humanBillCode = existingOp.humanBillCode,
                    revisionNumber = 0,
                    replayedFromDurableOperation = true
                )
            }
        }

        if (cancelReasonTh.isBlank()) {
            return AtomicCommitResult.Rejected(
                code = "CANCEL_REASON_REQUIRED",
                messageTh = "ต้องระบุเหตุผลในการยกเลิกบิล"
            )
        }

        val bill = getBillById(billId)
            ?: return AtomicCommitResult.Rejected(
                code = "BILL_NOT_FOUND",
                messageTh = "ไม่พบบิลที่ต้องการยกเลิก"
            )

        if (bill.status == BillStatus.CANCELLED.name || bill.activeRevisionId == null) {
            return AtomicCommitResult.Rejected(
                code = "BILL_ALREADY_CANCELLED",
                messageTh = "บิลนี้ถูกยกเลิกไปแล้ว"
            )
        }

        val activeRevisionId = bill.activeRevisionId
        val activeLines = getLinesForRevision(activeRevisionId)
        val grouped = activeLines.groupBy { it.numberTwoDigit to it.side }

        for ((key, linesForKey) in grouped) {
            val (numberTwoDigit, side) = key
            val counter = getNumberSideCounter(bill.roundId, numberTwoDigit, side) ?: continue
            var removeReceive = 0L
            var removePayout = 0L
            for (line in linesForKey) {
                removeReceive = Math.addExact(removeReceive, line.amountMinor)
                removePayout = Math.addExact(removePayout, line.provisionalPayoutMinor)
            }
            upsertNumberSideCounter(
                counter.copy(
                    activeReceiveTotalMinor = Math.subtractExact(counter.activeReceiveTotalMinor, removeReceive).coerceAtLeast(0L),
                    activePayoutTotalMinor = Math.subtractExact(counter.activePayoutTotalMinor, removePayout).coerceAtLeast(0L),
                    activeLineCount = (counter.activeLineCount - linesForKey.size).coerceAtLeast(0),
                    updatedAtEpochMs = nowEpochMs
                )
            )
        }

        updateBill(
            bill.copy(
                activeRevisionId = null,
                status = BillStatus.CANCELLED.name,
                cancelReasonTh = cancelReasonTh,
                cancelledAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs
            )
        )

        insertDurableOperation(
            DurableOperationEntity(
                operationKey = operationKey,
                operationType = "CANCEL_BILL",
                roundId = bill.roundId,
                policyVersion = 0L,
                quoteId = null,
                payloadSha256 = payloadSha256,
                status = DurableOperationStatus.COMPLETED.name,
                committedBillId = bill.billId,
                committedRevisionId = activeRevisionId,
                humanBillCode = bill.humanBillCode,
                rejectionCode = null,
                rejectionMessageTh = null,
                createdAtEpochMs = nowEpochMs,
                completedAtEpochMs = nowEpochMs
            )
        )

        insertAuditLog(
            AuditLogEntity(
                eventType = "BILL_CANCELLED",
                actorRole = actorId,
                roundId = bill.roundId,
                summaryTh = "ยกเลิกบิล ${bill.humanBillCode} เหตุผล: $cancelReasonTh (คงประวัติ Revision เดิมไว้)",
                createdAtEpochMs = nowEpochMs
            )
        )

        return AtomicCommitResult.Committed(
            billId = bill.billId,
            revisionId = activeRevisionId,
            humanBillCode = bill.humanBillCode,
            revisionNumber = bill.latestRevisionNumber,
            replayedFromDurableOperation = false
        )
    }

    /**
     * เปลี่ยนค่ามาตรฐาน (Default Policy) โดยสร้าง PolicyVersion รุ่นใหม่เสมอ (ไม่เขียนทับประวัติเดิม)
     * กฎ D07 & 05_UI ("inheritanceต่อช่องแยกapply-all/เปิดปิดรับ"):
     * - ค่าเริ่มต้น (applyAllClearRateAndCapOverrides = false): คัดลอก Override รายเลขเดิมทั้งหมดมายังรุ่นใหม่
     *   ทำให้ค่ามาตรฐานใหม่มีผลเฉพาะช่องที่เป็น null (Inherit) เท่านั้น ไม่ทับค่าที่ตั้ง Override รายเลขไว้
     * - หากผู้ดูแลเลือก applyAllClearRateAndCapOverrides = true อย่างชัดแจ้ง: จะล้างเฉพาะค่า Override อัตราจ่ายและเพดาน
     *   แต่ยังคงสถานะเปิด/ปิดรับรายเลข (overrideIsClosed) ไว้ ยกเว้นจะเลือก resetOpenCloseOverrides = true แยกต่างหาก
     */
    @Transaction
    suspend fun publishNewDefaultPolicyVersionTransaction(
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
        if (changeReasonTh.isBlank()) {
            return PolicyUpdateResult.Rejected("ต้องระบุเหตุผลก่อนเปลี่ยนนโยบายมาตรฐาน")
        }
        val round = getRoundById(roundId)
            ?: return PolicyUpdateResult.Rejected("ไม่พบงวดที่ต้องการเปลี่ยนนโยบาย")

        val oldVersion = round.activePolicyVersion
        val nextVersion = oldVersion + 1L

        val newPolicyEntity = PolicyVersionEntity(
            roundId = roundId,
            policyVersion = nextVersion,
            defaultTopRateExact = newTopRateExact,
            defaultBottomRateExact = newBottomRateExact,
            defaultReceiveCapMinor = newReceiveCapMinor,
            defaultPayoutCapMinor = newPayoutCapMinor,
            roundingMode = "HALF_UP",
            isDemoDefault = false,
            changeReason = changeReasonTh.trim(),
            createdAtEpochMs = nowEpochMs
        )
        insertPolicyVersion(newPolicyEntity)

        val existingOverrides = getOverridesForPolicyVersion(roundId, oldVersion)
        for (oldOverride in existingOverrides) {
            val nextRateOverride = if (applyAllClearRateAndCapOverrides) null else oldOverride.overrideRateExact
            val nextReceiveCapOverride = if (applyAllClearRateAndCapOverrides) null else oldOverride.overrideReceiveCapMinor
            val nextPayoutCapOverride = if (applyAllClearRateAndCapOverrides) null else oldOverride.overridePayoutCapMinor
            val nextClosedOverride = if (resetOpenCloseOverrides) null else oldOverride.overrideIsClosed

            if (nextRateOverride != null ||
                nextReceiveCapOverride != null ||
                nextPayoutCapOverride != null ||
                nextClosedOverride != null
            ) {
                upsertNumberPolicyOverride(
                    NumberPolicyOverrideEntity(
                        roundId = roundId,
                        policyVersion = nextVersion,
                        numberTwoDigit = oldOverride.numberTwoDigit,
                        side = oldOverride.side,
                        overrideRateExact = nextRateOverride,
                        overrideReceiveCapMinor = nextReceiveCapOverride,
                        overridePayoutCapMinor = nextPayoutCapOverride,
                        overrideIsClosed = nextClosedOverride,
                        overrideReasonTh = oldOverride.overrideReasonTh,
                        createdAtEpochMs = nowEpochMs
                    )
                )
            }
        }

        updateRound(
            round.copy(
                activePolicyVersion = nextVersion,
                updatedAtEpochMs = nowEpochMs
            )
        )

        insertAuditLog(
            AuditLogEntity(
                eventType = "POLICY_DEFAULTS_UPDATED",
                actorRole = actorId,
                roundId = roundId,
                summaryTh = "อัปเดตนโยบายมาตรฐานงวด ${round.roundCode} รุ่น #$oldVersion -> #$nextVersion (applyAllRateCap=$applyAllClearRateAndCapOverrides, resetOpenClose=$resetOpenCloseOverrides) เหตุผล: $changeReasonTh",
                createdAtEpochMs = nowEpochMs
            )
        )

        return PolicyUpdateResult.Updated(
            roundId = roundId,
            previousPolicyVersion = oldVersion,
            newPolicyVersion = nextVersion
        )
    }

    /**
     * ตั้งค่าหรือแก้ไข Override เฉพาะเลข/ด้านแบบ Per-Field Explicit Override
     * โดยสร้าง PolicyVersion รุ่นใหม่เพื่อบังคับให้ใบตรวจทาน (Review Quote) ที่ค้างอยู่ต้องตรวจทานใหม่เสมอ
     */
    @Transaction
    suspend fun savePerNumberExplicitOverrideTransaction(
        roundId: String,
        numberTwoDigit: String,
        side: String,
        overrideRateExact: String?,
        overrideReceiveCapMinor: Long?,
        overridePayoutCapMinor: Long?,
        overrideIsClosed: Boolean?,
        overrideReasonTh: String,
        actorId: String,
        nowEpochMs: Long
    ): PolicyUpdateResult {
        if (overrideReasonTh.isBlank()) {
            return PolicyUpdateResult.Rejected("ต้องระบุเหตุผลในการตั้งค่ารายเลข")
        }
        val round = getRoundById(roundId)
            ?: return PolicyUpdateResult.Rejected("ไม่พบงวดที่ต้องการตั้งค่ารายเลข")

        val currentPolicy = getPolicyVersion(roundId, round.activePolicyVersion)
            ?: return PolicyUpdateResult.Rejected("ไม่พบรุ่นนโยบายปัจจุบันของงวด")

        val oldVersion = round.activePolicyVersion
        val nextVersion = oldVersion + 1L

        insertPolicyVersion(
            currentPolicy.copy(
                policyRowId = 0L,
                policyVersion = nextVersion,
                changeReason = "ตั้งค่ารายเลข $numberTwoDigit ($side): ${overrideReasonTh.trim()}",
                createdAtEpochMs = nowEpochMs
            )
        )

        val previousOverrides = getOverridesForPolicyVersion(roundId, oldVersion)
        for (prev in previousOverrides) {
            if (prev.numberTwoDigit != numberTwoDigit || prev.side != side) {
                upsertNumberPolicyOverride(
                    prev.copy(
                        overrideId = 0L,
                        policyVersion = nextVersion,
                        createdAtEpochMs = nowEpochMs
                    )
                )
            }
        }

        if (overrideRateExact != null ||
            overrideReceiveCapMinor != null ||
            overridePayoutCapMinor != null ||
            overrideIsClosed != null
        ) {
            upsertNumberPolicyOverride(
                NumberPolicyOverrideEntity(
                    roundId = roundId,
                    policyVersion = nextVersion,
                    numberTwoDigit = numberTwoDigit,
                    side = side,
                    overrideRateExact = overrideRateExact,
                    overrideReceiveCapMinor = overrideReceiveCapMinor,
                    overridePayoutCapMinor = overridePayoutCapMinor,
                    overrideIsClosed = overrideIsClosed,
                    overrideReasonTh = overrideReasonTh.trim(),
                    createdAtEpochMs = nowEpochMs
                )
            )
        }

        updateRound(
            round.copy(
                activePolicyVersion = nextVersion,
                updatedAtEpochMs = nowEpochMs
            )
        )

        insertAuditLog(
            AuditLogEntity(
                eventType = "POLICY_NUMBER_OVERRIDE_UPDATED",
                actorRole = actorId,
                roundId = roundId,
                summaryTh = "ตั้งค่ารายเลข $numberTwoDigit ($side) รุ่น #$oldVersion -> #$nextVersion (rate=${overrideRateExact ?: "INHERIT"}, receiveCap=${overrideReceiveCapMinor ?: "INHERIT"}, closed=${overrideIsClosed ?: "INHERIT"}) เหตุผล: $overrideReasonTh",
                createdAtEpochMs = nowEpochMs
            )
        )

        return PolicyUpdateResult.Updated(
            roundId = roundId,
            previousPolicyVersion = oldVersion,
            newPolicyVersion = nextVersion
        )
    }

    /**
     * เปลี่ยนสถานะงวด (DRAFT / OPEN / PAUSED / CLOSED) พร้อมบันทึกเหตุผลใน Transaction เดียวกัน
     */
    @Transaction
    suspend fun transitionRoundStatusTransaction(
        roundId: String,
        targetStatus: RoundStatus,
        reasonTh: String,
        actorId: String,
        nowEpochMs: Long
    ): RoundTransitionResult {
        if (reasonTh.isBlank()) {
            return RoundTransitionResult.Rejected("ต้องระบุเหตุผลในการเปลี่ยนสถานะงวด")
        }
        val round = getRoundById(roundId)
            ?: return RoundTransitionResult.Rejected("ไม่พบงวดที่ต้องการเปลี่ยนสถานะ")

        val oldStatus = RoundStatus.fromStorageValue(round.status)
        if (oldStatus == RoundStatus.CLOSED && targetStatus != RoundStatus.CLOSED) {
            return RoundTransitionResult.Rejected("งวดที่ปิดแล้ว (CLOSED) ไม่สามารถเปิดรับใหม่ได้ ต้องใช้งวดใหม่หรือบันทึกการปรับปรุงที่ได้รับอนุมัติ")
        }

        updateRound(
            round.copy(
                status = targetStatus.name,
                updatedAtEpochMs = nowEpochMs
            )
        )

        insertAuditLog(
            AuditLogEntity(
                eventType = "ROUND_STATUS_CHANGED",
                actorRole = actorId,
                roundId = roundId,
                summaryTh = "เปลี่ยนสถานะงวด ${round.roundCode} จาก ${oldStatus.name} เป็น ${targetStatus.name} เหตุผล: $reasonTh",
                createdAtEpochMs = nowEpochMs
            )
        )

        return RoundTransitionResult.Transitioned(
            roundId = roundId,
            previousStatus = oldStatus,
            newStatus = targetStatus
        )
    }

    /**
     * กฎข้อ 01 & D08:
     * การแก้ไขหรือปรับปรุงข้อมูลของงวดที่ปิดแล้ว (Closed-round correction)
     * ต้องทำผ่านรายการปรับปรุงที่ได้รับอนุมัติ (Approved Adjustment) เท่านั้น
     */
    @Transaction
    suspend fun recordClosedRoundApprovedAdjustmentTransaction(
        approvalId: String,
        roundId: String,
        targetBillId: String,
        approvedByActorId: String,
        adjustmentReasonTh: String,
        nowEpochMs: Long
    ): ClosedRoundAdjustmentResult {
        if (adjustmentReasonTh.isBlank()) {
            return ClosedRoundAdjustmentResult.Rejected("ต้องระบุเหตุผลของการปรับปรุงงวดที่ปิดแล้ว")
        }
        val round = getRoundById(roundId)
            ?: return ClosedRoundAdjustmentResult.Rejected("ไม่พบงวดที่ต้องการปรับปรุง")

        if (round.status != RoundStatus.CLOSED.name) {
            return ClosedRoundAdjustmentResult.Rejected("คำสั่งนี้ใช้เฉพาะกับงวดที่ปิดแล้ว (CLOSED) เท่านั้น")
        }

        val actor = getLocalActorById(approvedByActorId)
        if (actor == null) {
            upsertLocalActor(
                LocalActorEntity(
                    actorId = approvedByActorId,
                    actorCode = approvedByActorId,
                    displayNameTh = "ผู้อนุมัติในเครื่อง ($approvedByActorId)",
                    primaryRole = "LOCAL_APPROVER",
                    isActive = true,
                    createdAtEpochMs = nowEpochMs
                )
            )
        }

        val approvalRecord = ApprovalRecordEntity(
            approvalId = approvalId,
            targetType = "CLOSED_ROUND_ADJUSTMENT",
            targetId = targetBillId,
            roundId = roundId,
            approvedByActorId = approvedByActorId,
            reasonTh = adjustmentReasonTh.trim(),
            approvedAtEpochMs = nowEpochMs
        )
        insertApprovalRecord(approvalRecord)

        insertAuditLog(
            AuditLogEntity(
                eventType = "CLOSED_ROUND_APPROVED_ADJUSTMENT",
                actorRole = approvedByActorId,
                roundId = roundId,
                summaryTh = "อนุมัติรายการปรับปรุงงวดปิดแล้ว (${round.roundCode}) อ้างอิง $targetBillId รออนุมัติ #$approvalId เหตุผล: $adjustmentReasonTh",
                createdAtEpochMs = nowEpochMs
            )
        )

        return ClosedRoundAdjustmentResult.Approved(
            approvalId = approvalId,
            roundId = roundId,
            targetBillId = targetBillId
        )
    }

    // =========================================================================
    // Phase 07 — Result Revision Approval, Idempotent Winning Entitlement & Reports Drilldown
    // =========================================================================

    @Query("SELECT * FROM result_revisions WHERE round_id = :roundId ORDER BY revision_number DESC")
    suspend fun getResultRevisionsForRound(roundId: String): List<ResultRevisionEntity>

    @Query(
        """
        SELECT * FROM result_revisions
        WHERE round_id = :roundId AND status = 'APPROVED'
        ORDER BY revision_number DESC
        LIMIT 1
        """
    )
    suspend fun getActiveApprovedResultRevision(roundId: String): ResultRevisionEntity?

    @Query("SELECT * FROM result_revisions WHERE result_revision_id = :resultRevisionId LIMIT 1")
    suspend fun getResultRevisionById(resultRevisionId: String): ResultRevisionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertResultRevision(resultRevision: ResultRevisionEntity)

    @Update
    suspend fun updateResultRevision(resultRevision: ResultRevisionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWinningEntitlements(entitlements: List<WinningEntitlementEntity>)

    @Query(
        """
        SELECT * FROM winning_entitlements
        WHERE result_revision_id = :resultRevisionId
        ORDER BY bill_id ASC, bill_line_id ASC
        """
    )
    suspend fun getWinningEntitlementsForRevision(resultRevisionId: String): List<WinningEntitlementEntity>

    @Query(
        """
        SELECT * FROM winning_entitlements
        WHERE result_revision_id = :resultRevisionId AND bill_id = :billId
        ORDER BY bill_line_id ASC
        """
    )
    suspend fun getWinningEntitlementsForBillInRevision(
        resultRevisionId: String,
        billId: String
    ): List<WinningEntitlementEntity>

    @Query(
        """
        SELECT * FROM payment_allocations
        WHERE target_type = :targetType AND target_id IN (:targetIds)
        """
    )
    suspend fun getPaymentAllocationsForTargets(
        targetType: String,
        targetIds: List<String>
    ): List<PaymentAllocationEntity>

    /**
     * ขั้นตอนที่ 1 ของการประกาศผลรางวัล (Preview Entitlement before Approval):
     * คำนวณสิทธิได้รับรางวัลตามผลรางวัลที่กรอก (winningTopTwoDigit, winningBottomTwoDigit)
     * เฉพาะจากบิลที่สถานะ ACTIVE และใช้ข้อมูลบรรทัดจาก activeRevisionId เท่านั้น
     * ตรวจสอบ Overflow ด้วย ExactMoneyMath.checkedAdd ทุกรายการ และยังไม่บันทึกลงฐานข้อมูล
     */
    @Transaction
    suspend fun previewResultEntitlementsForRound(
        roundId: String,
        winningTopTwoDigit: String,
        winningBottomTwoDigit: String
    ): ResultEntitlementPreviewOutcome {
        val validTop = try {
            NumberExpansionEngine.validateTwoDigitNumber(winningTopTwoDigit)
        } catch (e: IllegalArgumentException) {
            return ResultEntitlementPreviewOutcome.Rejected(
                code = "INVALID_WINNING_TOP",
                messageTh = "เลขรางวัล 2 ตัวบนไม่ถูกต้อง (${e.message})"
            )
        }

        val validBottom = try {
            NumberExpansionEngine.validateTwoDigitNumber(winningBottomTwoDigit)
        } catch (e: IllegalArgumentException) {
            return ResultEntitlementPreviewOutcome.Rejected(
                code = "INVALID_WINNING_BOTTOM",
                messageTh = "เลขรางวัล 2 ตัวล่างไม่ถูกต้อง (${e.message})"
            )
        }

        val round = getRoundById(roundId)
            ?: return ResultEntitlementPreviewOutcome.Rejected(
                code = "ROUND_NOT_FOUND",
                messageTh = "ไม่พบงวดที่ต้องการคำนวณผลรางวัล"
            )

        val allBills = searchBillsInRound(roundId, "")
        val activeBills = allBills.filter { it.status == BillStatus.ACTIVE.name && it.activeRevisionId != null }
        val cancelledBillCount = allBills.count { it.status == BillStatus.CANCELLED.name }

        var totalActiveReceiveMinor = 0L
        var topWinningPrincipalMinor = 0L
        var topEntitlementPayoutMinor = 0L
        var bottomWinningPrincipalMinor = 0L
        var bottomEntitlementPayoutMinor = 0L

        val previewMatchingLines = mutableListOf<PreviewWinningLineItem>()
        val winningBillIds = mutableSetOf<String>()

        for (bill in activeBills) {
            val activeRevId = bill.activeRevisionId ?: continue
            totalActiveReceiveMinor = ExactMoneyMath.checkedAdd(
                totalActiveReceiveMinor,
                bill.currentTotalAmountMinor
            )

            val lines = getLinesForRevision(activeRevId)
            for (line in lines) {
                val isTopMatch = line.side == BetSide.TOP.name && line.numberTwoDigit == validTop
                val isBottomMatch = line.side == BetSide.BOTTOM.name && line.numberTwoDigit == validBottom
                if (!isTopMatch && !isBottomMatch) continue

                // ตรวจซ้ำยอดจ่ายรายบรรทัดด้วย ExactMoneyMath เพื่อป้องกันข้อมูลเพี้ยน
                val verifiedRate = ExactMoneyMath.parseExactRate(line.rateSnapshotExact)
                val verifiedLineEntitlementMinor = ExactMoneyMath.computeProvisionalLinePayoutMinor(
                    amountMinor = line.amountMinor,
                    exactRate = verifiedRate
                )
                if (verifiedLineEntitlementMinor != line.provisionalPayoutMinor) {
                    return ResultEntitlementPreviewOutcome.Rejected(
                        code = "LINE_PAYOUT_SNAPSHOT_MISMATCH",
                        messageTh = "พบความไม่ตรงกันของยอดจ่ายต่อบรรทัดในบิล ${bill.humanBillCode} บรรทัดที่ ${line.lineNumber}"
                    )
                }

                if (isTopMatch) {
                    topWinningPrincipalMinor = ExactMoneyMath.checkedAdd(
                        topWinningPrincipalMinor,
                        line.amountMinor
                    )
                    topEntitlementPayoutMinor = ExactMoneyMath.checkedAdd(
                        topEntitlementPayoutMinor,
                        verifiedLineEntitlementMinor
                    )
                } else {
                    bottomWinningPrincipalMinor = ExactMoneyMath.checkedAdd(
                        bottomWinningPrincipalMinor,
                        line.amountMinor
                    )
                    bottomEntitlementPayoutMinor = ExactMoneyMath.checkedAdd(
                        bottomEntitlementPayoutMinor,
                        verifiedLineEntitlementMinor
                    )
                }

                winningBillIds.add(bill.billId)
                previewMatchingLines.add(
                    PreviewWinningLineItem(
                        billId = bill.billId,
                        humanBillCode = bill.humanBillCode,
                        customerNoteTh = bill.customerNoteTh,
                        billRevisionId = activeRevId,
                        billLineId = line.lineId,
                        lineNumber = line.lineNumber,
                        numberTwoDigit = line.numberTwoDigit,
                        side = line.side,
                        principalAmountMinor = line.amountMinor,
                        rateSnapshotExact = line.rateSnapshotExact,
                        entitlementPayoutMinor = verifiedLineEntitlementMinor
                    )
                )
            }
        }

        val totalEntitlementPayoutMinor = ExactMoneyMath.checkedAdd(
            topEntitlementPayoutMinor,
            bottomEntitlementPayoutMinor
        )

        return ResultEntitlementPreviewOutcome.ValidPreview(
            summary = ResultEntitlementPreviewSummary(
                roundId = round.roundId,
                roundCode = round.roundCode,
                winningTopTwoDigit = validTop,
                winningBottomTwoDigit = validBottom,
                activeBillCount = activeBills.size,
                cancelledBillCount = cancelledBillCount,
                totalActiveReceiveMinor = totalActiveReceiveMinor,
                winningBillCount = winningBillIds.size,
                winningLineCount = previewMatchingLines.size,
                topWinningPrincipalMinor = topWinningPrincipalMinor,
                topEntitlementPayoutMinor = topEntitlementPayoutMinor,
                bottomWinningPrincipalMinor = bottomWinningPrincipalMinor,
                bottomEntitlementPayoutMinor = bottomEntitlementPayoutMinor,
                totalEntitlementPayoutMinor = totalEntitlementPayoutMinor,
                matchingLines = previewMatchingLines
            )
        )
    }

    /**
     * ขั้นตอนที่ 2 ของการประกาศผลรางวัล (Approve Result Revision & Persist Idempotent Winning Entitlements):
     * - ตรวจสอบ local actor ก่อน
     * - ตรวจสอบ durable operation_key ก่อนเสมอ:
     *   - หาก operation_key เดิมแต่ payload_sha256 ต่างกัน -> คืน PayloadConflict
     *   - หาก operation_key เดิมและสำเร็จแล้ว -> คืนผลลัพธ์เดิมทันทีโดยไม่สร้าง ResultRevision หรือ WinningEntitlement ซ้ำ
     * - หากมี ResultRevision ที่สถานะ APPROVED อยู่แล้วในงวดนี้ และเลขรางวัล (บน, ล่าง) ตรงกับที่ส่งมาทุกประการ ->
     *   คืน ResultRevision เดิมแบบ Idempotent (replayedFromIdenticalActiveResult = true) โดยไม่สร้างแถวซ้ำหรือนับยอดซ้ำ
     * - หากเป็นการแก้ไขผลรางวัลใหม่ (เลขรางวัลเปลี่ยนจากรุ่น APPROVED เดิม) ->
     *   เปลี่ยนสถานะรุ่นเดิมเป็น SUPERSEDED (เก็บประวัติครบถ้วน) แล้วสร้าง ResultRevision รุ่นใหม่ พร้อมคำนวณ WinningEntitlement ใหม่ทั้งหมดใน Transaction เดียวกัน
     * - สิทธิได้รับรางวัล (Winning Entitlement) ไม่ถือว่าจ่ายเงินสดจริง (winningentitlement != paid)
     */
    @Transaction
    suspend fun executeApproveResultRevisionTransaction(
        request: ApproveResultRevisionRequest
    ): ResultApprovalOutcome {
        if (request.actorId.isBlank()) {
            return ResultApprovalOutcome.Rejected(
                code = "LOCAL_ACCESS_DENIED",
                messageTh = "ไม่พบรหัสผู้อนุมัติในเครื่อง"
            )
        }
        val actor = getLocalActorById(request.actorId)
        if (actor != null && !actor.isActive) {
            return ResultApprovalOutcome.Rejected(
                code = "LOCAL_ACTOR_INACTIVE",
                messageTh = "บัญชีผู้อนุมัติในเครื่องถูกระงับการใช้งาน"
            )
        }

        // 1. ตรวจสอบ operation_key เพื่อรองรับการกดซ้ำหรือฟื้นตัวหลังแอปถูกปิด (Process Death Replay)
        val existingOp = getDurableOperation(request.operationKey)
        if (existingOp != null) {
            if (existingOp.payloadSha256 != request.payloadSha256) {
                return ResultApprovalOutcome.PayloadConflict(
                    operationKey = request.operationKey,
                    existingPayloadSha256 = existingOp.payloadSha256,
                    incomingPayloadSha256 = request.payloadSha256,
                    messageTh = "พบการใช้ operation_key ซ้ำแต่ข้อมูลผลรางวัลไม่ตรงกับคำสั่งเดิม"
                )
            }
            if (existingOp.status == DurableOperationStatus.COMPLETED.name && existingOp.resultRefId != null) {
                val existingRev = getResultRevisionById(existingOp.resultRefId)
                if (existingRev != null) {
                    val existingEntitlements = getWinningEntitlementsForRevision(existingRev.resultRevisionId)
                    val totalEntitlementMinor = ExactMoneyMath.checkedSum(
                        existingEntitlements.map { it.entitlementPayoutMinor }
                    )
                    return ResultApprovalOutcome.Approved(
                        resultRevisionId = existingRev.resultRevisionId,
                        revisionNumber = existingRev.revisionNumber,
                        winningTopTwoDigit = existingRev.winningTopTwoDigit,
                        winningBottomTwoDigit = existingRev.winningBottomTwoDigit,
                        winningEntitlementCount = existingEntitlements.size,
                        totalEntitlementPayoutMinor = totalEntitlementMinor,
                        replayedFromDurableOperation = true,
                        replayedFromIdenticalActiveResult = false
                    )
                }
            }
        }

        if (request.reasonTh.isBlank()) {
            return ResultApprovalOutcome.Rejected(
                code = "MISSING_APPROVAL_REASON",
                messageTh = "ต้องระบุเหตุผลการอนุมัติหรือแก้ไขผลรางวัล"
            )
        }

        val previewOutcome = previewResultEntitlementsForRound(
            roundId = request.roundId,
            winningTopTwoDigit = request.winningTopTwoDigit,
            winningBottomTwoDigit = request.winningBottomTwoDigit
        )
        val previewSummary = when (previewOutcome) {
            is ResultEntitlementPreviewOutcome.Rejected -> {
                return ResultApprovalOutcome.Rejected(
                    code = previewOutcome.code,
                    messageTh = previewOutcome.messageTh
                )
            }
            is ResultEntitlementPreviewOutcome.ValidPreview -> previewOutcome.summary
        }

        // 2. ตรวจ Idempotency กรณีส่งเลขรางวัลชุดเดียวกับรุ่น APPROVED ปัจจุบันด้วย operation_key ใหม่
        val currentActiveRev = getActiveApprovedResultRevision(request.roundId)
        if (currentActiveRev != null &&
            currentActiveRev.winningTopTwoDigit == previewSummary.winningTopTwoDigit &&
            currentActiveRev.winningBottomTwoDigit == previewSummary.winningBottomTwoDigit
        ) {
            val existingEntitlements = getWinningEntitlementsForRevision(currentActiveRev.resultRevisionId)
            val totalEntitlementMinor = ExactMoneyMath.checkedSum(
                existingEntitlements.map { it.entitlementPayoutMinor }
            )
            if (existingOp == null) {
                insertDurableOperation(
                    DurableOperationEntity(
                        operationKey = request.operationKey,
                        operationType = "RESULT_REVISION_APPROVE",
                        payloadSha256 = request.payloadSha256,
                        status = DurableOperationStatus.COMPLETED.name,
                        resultRefId = currentActiveRev.resultRevisionId,
                        rejectionCode = null,
                        createdAtEpochMs = request.nowEpochMs
                    )
                )
            }
            return ResultApprovalOutcome.Approved(
                resultRevisionId = currentActiveRev.resultRevisionId,
                revisionNumber = currentActiveRev.revisionNumber,
                winningTopTwoDigit = currentActiveRev.winningTopTwoDigit,
                winningBottomTwoDigit = currentActiveRev.winningBottomTwoDigit,
                winningEntitlementCount = existingEntitlements.size,
                totalEntitlementPayoutMinor = totalEntitlementMinor,
                replayedFromDurableOperation = false,
                replayedFromIdenticalActiveResult = true
            )
        }

        if (actor == null) {
            upsertLocalActor(
                LocalActorEntity(
                    actorId = request.actorId,
                    actorCode = request.actorId,
                    displayNameTh = "ผู้อนุมัติผลรางวัลในเครื่อง (${request.actorId})",
                    primaryRole = "LOCAL_APPROVER",
                    isActive = true,
                    createdAtEpochMs = request.nowEpochMs
                )
            )
        }

        // 3. หากมีผลรางวัลรุ่น APPROVED เดิมที่ตัวเลขต่างออกไป ให้เปลี่ยนสถานะเป็น SUPERSEDED (เก็บประวัติครบถ้วน)
        val existingRevisions = getResultRevisionsForRound(request.roundId)
        for (oldRev in existingRevisions) {
            if (oldRev.status == ResultRevisionStatus.APPROVED.name) {
                updateResultRevision(
                    oldRev.copy(status = ResultRevisionStatus.SUPERSEDED.name)
                )
            }
        }

        val nextRevisionNumber = (existingRevisions.maxOfOrNull { it.revisionNumber } ?: 0) + 1

        insertApprovalRecord(
            ApprovalRecordEntity(
                approvalId = request.approvalId,
                targetType = "RESULT_REVISION_APPROVAL",
                targetId = request.newResultRevisionId,
                roundId = request.roundId,
                approvedByActorId = request.actorId,
                reasonTh = request.reasonTh.trim(),
                approvedAtEpochMs = request.nowEpochMs
            )
        )

        val newRevisionEntity = ResultRevisionEntity(
            resultRevisionId = request.newResultRevisionId,
            roundId = request.roundId,
            revisionNumber = nextRevisionNumber,
            winningTopTwoDigit = previewSummary.winningTopTwoDigit,
            winningBottomTwoDigit = previewSummary.winningBottomTwoDigit,
            status = ResultRevisionStatus.APPROVED.name,
            approvalId = request.approvalId,
            reasonTh = request.reasonTh.trim(),
            createdAtEpochMs = request.nowEpochMs
        )
        insertResultRevision(newRevisionEntity)

        val entitlementEntities = previewSummary.matchingLines.map { item ->
            WinningEntitlementEntity(
                entitlementId = "ent-${request.newResultRevisionId}-${item.billLineId}",
                roundId = request.roundId,
                resultRevisionId = request.newResultRevisionId,
                billId = item.billId,
                billRevisionId = item.billRevisionId,
                billLineId = item.billLineId,
                numberTwoDigit = item.numberTwoDigit,
                side = item.side,
                principalAmountMinor = item.principalAmountMinor,
                rateSnapshotExact = item.rateSnapshotExact,
                entitlementPayoutMinor = item.entitlementPayoutMinor,
                createdAtEpochMs = request.nowEpochMs
            )
        }
        if (entitlementEntities.isNotEmpty()) {
            insertWinningEntitlements(entitlementEntities)
        }

        insertDurableOperation(
            DurableOperationEntity(
                operationKey = request.operationKey,
                operationType = "RESULT_REVISION_APPROVE",
                payloadSha256 = request.payloadSha256,
                status = DurableOperationStatus.COMPLETED.name,
                resultRefId = request.newResultRevisionId,
                rejectionCode = null,
                createdAtEpochMs = request.nowEpochMs
            )
        )

        insertAuditLog(
            AuditLogEntity(
                eventType = "RESULT_REVISION_APPROVED",
                actorRole = request.actorId,
                roundId = request.roundId,
                summaryTh = "อนุมัติผลรางวัลงวด ${previewSummary.roundCode} รุ่นที่ #$nextRevisionNumber (บน=${previewSummary.winningTopTwoDigit}, ล่าง=${previewSummary.winningBottomTwoDigit}) สิทธิได้รับรางวัลรวม ${previewSummary.totalEntitlementPayoutMinor} สตางค์ (${entitlementEntities.size} รายการ • ยังไม่ถือว่าจ่ายเงินสดจริง) เหตุผล: ${request.reasonTh.trim()}",
                createdAtEpochMs = request.nowEpochMs
            )
        )

        return ResultApprovalOutcome.Approved(
            resultRevisionId = request.newResultRevisionId,
            revisionNumber = nextRevisionNumber,
            winningTopTwoDigit = previewSummary.winningTopTwoDigit,
            winningBottomTwoDigit = previewSummary.winningBottomTwoDigit,
            winningEntitlementCount = entitlementEntities.size,
            totalEntitlementPayoutMinor = previewSummary.totalEntitlementPayoutMinor,
            replayedFromDurableOperation = false,
            replayedFromIdenticalActiveResult = false
        )
    }
}

data class PersistedLineCommitInput(
    val numberTwoDigit: String,
    val side: String,
    val amountMinor: Long,
    val rateSnapshotExact: String,
    val provisionalPayoutMinor: Long,
    val roundingMode: String = "HALF_UP"
)

data class AtomicBillCommitRequest(
    val operationKey: String,
    val payloadSha256: String,
    val quoteId: String,
    val cartIdToClear: String?,
    val roundId: String,
    val expectedPolicyVersion: Long,
    val newBillId: String,
    val newRevisionId: String,
    val explicitHumanBillCode: String? = null,
    val customerNoteTh: String,
    val actorId: String,
    val lines: List<PersistedLineCommitInput>,
    val nowEpochMs: Long
)

data class AtomicBillEditRequest(
    val operationKey: String,
    val payloadSha256: String,
    val billId: String,
    val newRevisionId: String,
    val expectedPolicyVersion: Long,
    val revisionReasonTh: String,
    val actorId: String,
    val replacementLines: List<PersistedLineCommitInput>,
    val nowEpochMs: Long
)

sealed interface AtomicCommitResult {
    data class Committed(
        val billId: String,
        val revisionId: String,
        val humanBillCode: String,
        val revisionNumber: Int,
        val replayedFromDurableOperation: Boolean
    ) : AtomicCommitResult

    data class PayloadConflict(
        val operationKey: String,
        val existingPayloadSha256: String,
        val incomingPayloadSha256: String,
        val messageTh: String
    ) : AtomicCommitResult

    data class Rejected(
        val code: String,
        val messageTh: String
    ) : AtomicCommitResult
}

sealed interface PolicyUpdateResult {
    data class Updated(
        val roundId: String,
        val previousPolicyVersion: Long,
        val newPolicyVersion: Long
    ) : PolicyUpdateResult

    data class Rejected(
        val reasonTh: String
    ) : PolicyUpdateResult
}

sealed interface RoundTransitionResult {
    data class Transitioned(
        val roundId: String,
        val previousStatus: RoundStatus,
        val newStatus: RoundStatus
    ) : RoundTransitionResult

    data class Rejected(
        val reasonTh: String
    ) : RoundTransitionResult
}

sealed interface ClosedRoundAdjustmentResult {
    data class Approved(
        val approvalId: String,
        val roundId: String,
        val targetBillId: String
    ) : ClosedRoundAdjustmentResult

    data class Rejected(
        val reasonTh: String
    ) : ClosedRoundAdjustmentResult
}

data class PreviewWinningLineItem(
    val billId: String,
    val humanBillCode: String,
    val customerNoteTh: String,
    val billRevisionId: String,
    val billLineId: Long,
    val lineNumber: Int,
    val numberTwoDigit: String,
    val side: String,
    val principalAmountMinor: Long,
    val rateSnapshotExact: String,
    val entitlementPayoutMinor: Long
)

data class ResultEntitlementPreviewSummary(
    val roundId: String,
    val roundCode: String,
    val winningTopTwoDigit: String,
    val winningBottomTwoDigit: String,
    val activeBillCount: Int,
    val cancelledBillCount: Int,
    val totalActiveReceiveMinor: Long,
    val winningBillCount: Int,
    val winningLineCount: Int,
    val topWinningPrincipalMinor: Long,
    val topEntitlementPayoutMinor: Long,
    val bottomWinningPrincipalMinor: Long,
    val bottomEntitlementPayoutMinor: Long,
    val totalEntitlementPayoutMinor: Long,
    val matchingLines: List<PreviewWinningLineItem>
)

sealed interface ResultEntitlementPreviewOutcome {
    data class ValidPreview(
        val summary: ResultEntitlementPreviewSummary
    ) : ResultEntitlementPreviewOutcome

    data class Rejected(
        val code: String,
        val messageTh: String
    ) : ResultEntitlementPreviewOutcome
}

data class ApproveResultRevisionRequest(
    val operationKey: String,
    val payloadSha256: String,
    val roundId: String,
    val newResultRevisionId: String,
    val approvalId: String,
    val winningTopTwoDigit: String,
    val winningBottomTwoDigit: String,
    val reasonTh: String,
    val actorId: String,
    val nowEpochMs: Long
)

sealed interface ResultApprovalOutcome {
    data class Approved(
        val resultRevisionId: String,
        val revisionNumber: Int,
        val winningTopTwoDigit: String,
        val winningBottomTwoDigit: String,
        val winningEntitlementCount: Int,
        val totalEntitlementPayoutMinor: Long,
        val replayedFromDurableOperation: Boolean,
        val replayedFromIdenticalActiveResult: Boolean
    ) : ResultApprovalOutcome

    data class PayloadConflict(
        val operationKey: String,
        val existingPayloadSha256: String,
        val incomingPayloadSha256: String,
        val messageTh: String
    ) : ResultApprovalOutcome

    data class Rejected(
        val code: String,
        val messageTh: String
    ) : ResultApprovalOutcome
}
