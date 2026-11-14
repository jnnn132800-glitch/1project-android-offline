package com.oneproject.offline.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.oneproject.offline.core.money.ExactMoneyMath
import com.oneproject.offline.data.local.entity.AccountScope
import com.oneproject.offline.data.local.entity.AuditLogEntity
import com.oneproject.offline.data.local.entity.BillEntity
import com.oneproject.offline.data.local.entity.BillStatus
import com.oneproject.offline.data.local.entity.LedgerAccountEntity
import com.oneproject.offline.data.local.entity.LedgerEntryDirection
import com.oneproject.offline.data.local.entity.LedgerEntryEntity
import com.oneproject.offline.data.local.entity.LedgerTransactionEntity
import com.oneproject.offline.data.local.entity.PaymentAllocationEntity
import com.oneproject.offline.data.local.entity.PaymentRecordEntity
import com.oneproject.offline.data.local.entity.WinningEntitlementEntity

@Dao
interface LedgerAndAccountingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLedgerAccount(account: LedgerAccountEntity)

    @Query("SELECT * FROM ledger_accounts ORDER BY account_code ASC")
    suspend fun getAllLedgerAccounts(): List<LedgerAccountEntity>

    @Query("SELECT * FROM ledger_accounts WHERE account_id = :accountId LIMIT 1")
    suspend fun getLedgerAccount(accountId: String): LedgerAccountEntity?

    @Query("SELECT * FROM ledger_transactions WHERE transaction_id = :transactionId LIMIT 1")
    suspend fun getLedgerTransaction(transactionId: String): LedgerTransactionEntity?

    @Query("SELECT * FROM ledger_transactions WHERE operation_key = :operationKey LIMIT 1")
    suspend fun getLedgerTransactionByOperationKey(operationKey: String): LedgerTransactionEntity?

    @Query("SELECT * FROM ledger_transactions WHERE reverses_transaction_id = :originalTransactionId LIMIT 1")
    suspend fun findReversalForTransaction(originalTransactionId: String): LedgerTransactionEntity?

    @Query(
        """
        SELECT * FROM ledger_transactions
        WHERE (:roundId IS NULL OR round_id = :roundId OR round_id IS NULL)
        ORDER BY posted_at_epoch_ms DESC
        """
    )
    suspend fun getTransactionsForRound(roundId: String?): List<LedgerTransactionEntity>

    @Query("SELECT * FROM ledger_entries WHERE transaction_id = :transactionId ORDER BY entry_sequence ASC")
    suspend fun getEntriesForTransaction(transactionId: String): List<LedgerEntryEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLedgerTransaction(transaction: LedgerTransactionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLedgerEntries(entries: List<LedgerEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPaymentRecord(payment: PaymentRecordEntity)

    @Update
    suspend fun updatePaymentRecord(payment: PaymentRecordEntity)

    @Query("SELECT * FROM payment_records WHERE payment_id = :paymentId LIMIT 1")
    suspend fun getPaymentRecordById(paymentId: String): PaymentRecordEntity?

    @Query("SELECT * FROM payment_records WHERE round_id = :roundId ORDER BY recorded_at_epoch_ms DESC")
    suspend fun getPaymentRecordsForRound(roundId: String): List<PaymentRecordEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPaymentAllocation(allocation: PaymentAllocationEntity)

    @Query("SELECT * FROM payment_allocations WHERE payment_id = :paymentId ORDER BY created_at_epoch_ms ASC")
    suspend fun getAllocationsForPayment(paymentId: String): List<PaymentAllocationEntity>

    @Query(
        """
        SELECT * FROM payment_allocations
        WHERE target_type = :targetType AND target_id = :targetId
        ORDER BY created_at_epoch_ms ASC
        """
    )
    suspend fun getAllocationsForTarget(
        targetType: String,
        targetId: String
    ): List<PaymentAllocationEntity>

    @Query("SELECT * FROM bills WHERE bill_id = :billId LIMIT 1")
    suspend fun getBillByIdForAllocation(billId: String): BillEntity?

    @Query("SELECT * FROM winning_entitlements WHERE entitlement_id = :entitlementId LIMIT 1")
    suspend fun getWinningEntitlementByIdForAllocation(entitlementId: String): WinningEntitlementEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAuditLog(audit: AuditLogEntity): Long

    /**
     * สร้างผังบัญชีสาธิต (Demo Chart of Accounts — D09 ยังไม่อนุมัติผังบัญชีจริง)
     * หากมีบัญชีอยู่แล้วจะไม่เขียนทับข้อมูลธุรกรรมเดิม
     */
    @Transaction
    suspend fun ensureDemoChartOfAccountsSeeded(nowEpochMs: Long): List<LedgerAccountEntity> {
        val existing = getAllLedgerAccounts()
        if (existing.isNotEmpty()) {
            return existing
        }

        val demoAccounts = listOf(
            LedgerAccountEntity(
                accountId = DEMO_ACC_ORDER_CASH,
                accountCode = "1101-DEMO",
                accountNameTh = "เงินสดรับ/จ่ายรายการบิลสาธิต [DEMO]",
                accountScope = AccountScope.ORDER_SETTLEMENT.name,
                currencyCode = "THB",
                isDemoChart = true,
                createdAtEpochMs = nowEpochMs
            ),
            LedgerAccountEntity(
                accountId = DEMO_ACC_BILL_RECEIVABLE,
                accountCode = "1201-DEMO",
                accountNameTh = "ลูกหนี้ค่าซื้อบิลค้างเก็บสาธิต [DEMO]",
                accountScope = AccountScope.ORDER_SETTLEMENT.name,
                currencyCode = "THB",
                isDemoChart = true,
                createdAtEpochMs = nowEpochMs
            ),
            LedgerAccountEntity(
                accountId = DEMO_ACC_ENTITLEMENT_PAYABLE,
                accountCode = "2101-DEMO",
                accountNameTh = "เจ้าหนี้สิทธิรางวัลค้างจ่ายสาธิต [DEMO]",
                accountScope = AccountScope.ORDER_SETTLEMENT.name,
                currencyCode = "THB",
                isDemoChart = true,
                createdAtEpochMs = nowEpochMs
            ),
            LedgerAccountEntity(
                accountId = DEMO_ACC_ORDER_REVENUE,
                accountCode = "4101-DEMO",
                accountNameTh = "รายได้ยอดรับรายการบิลสาธิต [DEMO]",
                accountScope = AccountScope.ORDER_SETTLEMENT.name,
                currencyCode = "THB",
                isDemoChart = true,
                createdAtEpochMs = nowEpochMs
            ),
            LedgerAccountEntity(
                accountId = DEMO_ACC_ENTITLEMENT_EXPENSE,
                accountCode = "5101-DEMO",
                accountNameTh = "ค่าใช้จ่ายสิทธิรางวัลสาธิต [DEMO]",
                accountScope = AccountScope.ORDER_SETTLEMENT.name,
                currencyCode = "THB",
                isDemoChart = true,
                createdAtEpochMs = nowEpochMs
            ),
            LedgerAccountEntity(
                accountId = DEMO_ACC_OPERATING_DRAWER_CASH,
                accountCode = "1001-DEMO",
                accountNameTh = "เงินสดในลิ้นชักเครื่องสาธิต [DEMO]",
                accountScope = AccountScope.OPERATING_CASH.name,
                currencyCode = "THB",
                isDemoChart = true,
                createdAtEpochMs = nowEpochMs
            ),
            LedgerAccountEntity(
                accountId = DEMO_ACC_OPERATING_RESERVE_EQUITY,
                accountCode = "3001-DEMO",
                accountNameTh = "ทุนสำรองเงินสดประจำเครื่องสาธิต [DEMO]",
                accountScope = AccountScope.OPERATING_CASH.name,
                currencyCode = "THB",
                isDemoChart = true,
                createdAtEpochMs = nowEpochMs
            ),
            LedgerAccountEntity(
                accountId = DEMO_ACC_COUNTERPARTY_RECEIVABLE,
                accountCode = "1301-DEMO",
                accountNameTh = "ลูกหนี้คู่สัญญาตีออกสาธิต [DEMO]",
                accountScope = AccountScope.COUNTERPARTY_SETTLEMENT.name,
                currencyCode = "THB",
                isDemoChart = true,
                createdAtEpochMs = nowEpochMs
            ),
            LedgerAccountEntity(
                accountId = DEMO_ACC_COUNTERPARTY_PAYABLE,
                accountCode = "2301-DEMO",
                accountNameTh = "เจ้าหนี้คู่สัญญาตีออกสาธิต [DEMO]",
                accountScope = AccountScope.COUNTERPARTY_SETTLEMENT.name,
                currencyCode = "THB",
                isDemoChart = true,
                createdAtEpochMs = nowEpochMs
            )
        )

        for (account in demoAccounts) {
            upsertLedgerAccount(account)
        }
        return getAllLedgerAccounts()
    }

    /**
     * โพสต์รายการบัญชีคู่ (Balanced Double-Entry Posting) ภายใน Room Transaction เดียวกัน
     * กฎข้อ 01 & 04_SPEC:
     * - ยอดรวม DEBIT ต้องเท่ากับยอดรวม CREDIT ทุกสตางค์ (ตรวจด้วย Math.addExact)
     * - ทุกบรรทัดต้องอยู่ในสกุลเงิน (currency_code) และขอบเขตบัญชี (account_scope) เดียวกัน
     * - การกลับรายการต้องอ้างอิง reverses_transaction_id ที่สลับฝั่ง DEBIT/CREDIT ตรงกัน
     */
    @Transaction
    suspend fun postBalancedTransaction(
        transactionId: String,
        operationKey: String,
        roundId: String?,
        currencyCode: String,
        accountScope: String,
        reversesTransactionId: String?,
        memoTh: String,
        postedByActorId: String,
        entries: List<LedgerEntryDraft>,
        nowEpochMs: Long
    ): LedgerPostingResult {
        if (memoTh.isBlank()) {
            return LedgerPostingResult.Rejected("ต้องระบุคำอธิบายรายการบัญชี (Memo)")
        }

        val existingTx = getLedgerTransactionByOperationKey(operationKey)
        if (existingTx != null) {
            return LedgerPostingResult.Posted(
                transactionId = existingTx.transactionId,
                totalBalancedMinor = existingTx.totalBalancedMinor,
                replayed = true
            )
        }

        if (entries.size < 2) {
            return LedgerPostingResult.Rejected("ต้องมีรายการเดบิตและเครดิตอย่างน้อย 2 บรรทัด")
        }

        var debitSum = 0L
        var creditSum = 0L

        for (entry in entries) {
            if (entry.amountMinor <= 0L) {
                return LedgerPostingResult.Rejected("จำนวนเงินในรายการบัญชีต้องมากกว่า 0 สตางค์")
            }
            val account = getLedgerAccount(entry.accountId)
                ?: return LedgerPostingResult.Rejected("ไม่พบบัญชี ${entry.accountId} ในผังบัญชี")

            if (account.currencyCode != currencyCode || account.accountScope != accountScope) {
                return LedgerPostingResult.Rejected(
                    "บัญชี ${account.accountCode} มีสกุลเงินหรือขอบเขตบัญชีไม่ตรงกับธุรกรรม ($currencyCode / $accountScope)"
                )
            }

            try {
                when (entry.direction) {
                    LedgerEntryDirection.DEBIT -> debitSum = Math.addExact(debitSum, entry.amountMinor)
                    LedgerEntryDirection.CREDIT -> creditSum = Math.addExact(creditSum, entry.amountMinor)
                }
            } catch (overflow: ArithmeticException) {
                return LedgerPostingResult.Rejected("ยอดรวมรายการบัญชีเกินขอบเขต 64-bit Long")
            }
        }

        if (debitSum != creditSum || debitSum <= 0L) {
            return LedgerPostingResult.Rejected(
                "รายการบัญชีไม่สมดุล: เดบิต ($debitSum) != เครดิต ($creditSum)"
            )
        }

        if (reversesTransactionId != null) {
            val originalTx = getLedgerTransaction(reversesTransactionId)
                ?: return LedgerPostingResult.Rejected("ไม่พบรายการบัญชีต้นทางที่ต้องการกลับรายการ ($reversesTransactionId)")
            if (originalTx.reversesTransactionId != null) {
                return LedgerPostingResult.Rejected("ไม่สามารถกลับรายการของรายการที่เป็น Reversal ซ้ำได้")
            }
            val existingReversal = findReversalForTransaction(reversesTransactionId)
            if (existingReversal != null) {
                return LedgerPostingResult.Rejected(
                    "รายการบัญชีต้นทาง $reversesTransactionId ถูกกลับรายการไปแล้วที่ ${existingReversal.transactionId}"
                )
            }
            if (originalTx.totalBalancedMinor != debitSum ||
                originalTx.currencyCode != currencyCode ||
                originalTx.accountScope != accountScope
            ) {
                return LedgerPostingResult.Rejected("รายการกลับบัญชีมียอดรวม สกุลเงิน หรือขอบเขตไม่ตรงกับรายการต้นทาง")
            }
        }

        val txEntity = LedgerTransactionEntity(
            transactionId = transactionId,
            operationKey = operationKey,
            roundId = roundId,
            currencyCode = currencyCode,
            accountScope = accountScope,
            totalBalancedMinor = debitSum,
            reversesTransactionId = reversesTransactionId,
            memoTh = memoTh.trim(),
            postedByActorId = postedByActorId,
            postedAtEpochMs = nowEpochMs
        )

        val entryEntities = entries.mapIndexed { idx, draft ->
            LedgerEntryEntity(
                transactionId = transactionId,
                entrySequence = idx + 1,
                accountId = draft.accountId,
                direction = draft.direction.name,
                amountMinor = draft.amountMinor,
                currencyCode = currencyCode,
                accountScope = accountScope
            )
        }

        insertLedgerTransaction(txEntity)
        insertLedgerEntries(entryEntities)
        insertAuditLog(
            AuditLogEntity(
                eventType = if (reversesTransactionId == null) "LEDGER_POSTED" else "LEDGER_REVERSAL_POSTED",
                actorRole = postedByActorId,
                roundId = roundId,
                summaryTh = "โพสต์รายการบัญชี $transactionId ยอดสมดุล $debitSum สตางค์ (${memoTh.trim()})",
                createdAtEpochMs = nowEpochMs
            )
        )

        return LedgerPostingResult.Posted(
            transactionId = transactionId,
            totalBalancedMinor = debitSum,
            replayed = false
        )
    }

    /**
     * กฎข้อ 01 & 04_SPEC ("reversalsnotedit"):
     * ห้ามแก้ไขทับรายการบัญชีที่โพสต์แล้ว การยกเลิก/แก้ไขต้องสร้าง Linked Reversal Transaction
     * ที่สลับฝั่ง DEBIT <-> CREDIT ทุกบรรทัดของรายการต้นทางและอ้างอิง reverses_transaction_id
     */
    @Transaction
    suspend fun postLinkedReversalTransaction(
        newReversalTransactionId: String,
        operationKey: String,
        originalTransactionId: String,
        reversalReasonTh: String,
        postedByActorId: String,
        nowEpochMs: Long
    ): LedgerPostingResult {
        if (reversalReasonTh.isBlank()) {
            return LedgerPostingResult.Rejected("ต้องระบุเหตุผลการกลับรายการบัญชี (Reversal Reason)")
        }

        val existingByOpKey = getLedgerTransactionByOperationKey(operationKey)
        if (existingByOpKey != null) {
            return LedgerPostingResult.Posted(
                transactionId = existingByOpKey.transactionId,
                totalBalancedMinor = existingByOpKey.totalBalancedMinor,
                replayed = true
            )
        }

        val originalTx = getLedgerTransaction(originalTransactionId)
            ?: return LedgerPostingResult.Rejected("ไม่พบรายการบัญชีต้นทางรหัส $originalTransactionId")

        if (originalTx.reversesTransactionId != null) {
            return LedgerPostingResult.Rejected("ไม่สามารถกลับรายการของรายการที่เป็น Reversal ได้")
        }

        val alreadyReversed = findReversalForTransaction(originalTransactionId)
        if (alreadyReversed != null) {
            return LedgerPostingResult.Rejected(
                "รายการบัญชี $originalTransactionId ถูกกลับรายการไปแล้วโดย ${alreadyReversed.transactionId}"
            )
        }

        val originalEntries = getEntriesForTransaction(originalTransactionId)
        if (originalEntries.size < 2) {
            return LedgerPostingResult.Rejected("ไม่พบบรรทัดรายการบัญชีต้นทางสำหรับสร้างรายการกลับบัญชี")
        }

        val mirrorDrafts = originalEntries.map { orig ->
            val flippedDirection = if (orig.direction == LedgerEntryDirection.DEBIT.name) {
                LedgerEntryDirection.CREDIT
            } else {
                LedgerEntryDirection.DEBIT
            }
            LedgerEntryDraft(
                accountId = orig.accountId,
                direction = flippedDirection,
                amountMinor = orig.amountMinor
            )
        }

        return postBalancedTransaction(
            transactionId = newReversalTransactionId,
            operationKey = operationKey,
            roundId = originalTx.roundId,
            currencyCode = originalTx.currencyCode,
            accountScope = originalTx.accountScope,
            reversesTransactionId = originalTransactionId,
            memoTh = "กลับรายการบัญชีของ $originalTransactionId เหตุผล: ${reversalReasonTh.trim()}",
            postedByActorId = postedByActorId,
            entries = mirrorDrafts,
            nowEpochMs = nowEpochMs
        )
    }

    /**
     * บันทึกการรับ/จ่ายเงินสดด้วยมือ พร้อมโพสต์รายการบัญชีคู่สมดุลใน Room Transaction เดียวกัน
     * - INFLOW_COLLECTION: รับเงินสดจากลูกค้า (DEBIT เงินสด 1101-DEMO / CREDIT ลูกหนี้บิล 1201-DEMO)
     * - OUTFLOW_ENTITLEMENT_PAYOUT: จ่ายเงินสดรางวัลให้ลูกค้า (DEBIT เจ้าหนี้รางวัล 2101-DEMO / CREDIT เงินสด 1101-DEMO)
     */
    @Transaction
    suspend fun recordPaymentWithLedgerPostingTransaction(
        paymentId: String,
        ledgerTransactionId: String,
        operationKey: String,
        roundId: String,
        paymentDirection: String,
        amountMinor: Long,
        evidenceNoteTh: String,
        actorId: String,
        nowEpochMs: Long
    ): PaymentRecordResult {
        if (amountMinor <= 0L) {
            return PaymentRecordResult.Rejected("จำนวนเงินรับ/จ่ายต้องมากกว่า 0 สตางค์")
        }
        if (evidenceNoteTh.isBlank()) {
            return PaymentRecordResult.Rejected("ต้องระบุหลักฐานหรือหมายเหตุการรับ/จ่ายเงินสด")
        }

        ensureDemoChartOfAccountsSeeded(nowEpochMs)

        val entries = when (paymentDirection) {
            PAYMENT_DIRECTION_INFLOW_COLLECTION -> listOf(
                LedgerEntryDraft(
                    accountId = DEMO_ACC_ORDER_CASH,
                    direction = LedgerEntryDirection.DEBIT,
                    amountMinor = amountMinor
                ),
                LedgerEntryDraft(
                    accountId = DEMO_ACC_BILL_RECEIVABLE,
                    direction = LedgerEntryDirection.CREDIT,
                    amountMinor = amountMinor
                )
            )

            PAYMENT_DIRECTION_OUTFLOW_ENTITLEMENT -> listOf(
                LedgerEntryDraft(
                    accountId = DEMO_ACC_ENTITLEMENT_PAYABLE,
                    direction = LedgerEntryDirection.DEBIT,
                    amountMinor = amountMinor
                ),
                LedgerEntryDraft(
                    accountId = DEMO_ACC_ORDER_CASH,
                    direction = LedgerEntryDirection.CREDIT,
                    amountMinor = amountMinor
                )
            )

            else -> return PaymentRecordResult.Rejected("ทิศทางการชำระเงินไม่ถูกต้อง ($paymentDirection)")
        }

        val ledgerResult = postBalancedTransaction(
            transactionId = ledgerTransactionId,
            operationKey = operationKey,
            roundId = roundId,
            currencyCode = "THB",
            accountScope = AccountScope.ORDER_SETTLEMENT.name,
            reversesTransactionId = null,
            memoTh = "บันทึกชำระเงินสด $paymentId ($paymentDirection): ${evidenceNoteTh.trim()}",
            postedByActorId = actorId,
            entries = entries,
            nowEpochMs = nowEpochMs
        )

        when (ledgerResult) {
            is LedgerPostingResult.Rejected -> {
                return PaymentRecordResult.Rejected(ledgerResult.reasonTh)
            }

            is LedgerPostingResult.Posted -> {
                val existingPayment = getPaymentRecordById(paymentId)
                if (existingPayment != null) {
                    return PaymentRecordResult.Recorded(
                        paymentId = existingPayment.paymentId,
                        ledgerTransactionId = existingPayment.ledgerTransactionId,
                        amountMinor = existingPayment.amountMinor
                    )
                }

                val paymentEntity = PaymentRecordEntity(
                    paymentId = paymentId,
                    roundId = roundId,
                    paymentDirection = paymentDirection,
                    amountMinor = amountMinor,
                    allocatedTotalMinor = 0L,
                    ledgerTransactionId = ledgerResult.transactionId,
                    evidenceNoteTh = evidenceNoteTh.trim(),
                    recordedAtEpochMs = nowEpochMs
                )
                insertPaymentRecord(paymentEntity)

                return PaymentRecordResult.Recorded(
                    paymentId = paymentId,
                    ledgerTransactionId = ledgerResult.transactionId,
                    amountMinor = amountMinor
                )
            }
        }
    }

    /**
     * จัดสรรยอดเงินสดเข้ากับบิล (BILL_RECEIVABLE) หรือสิทธิได้รับรางวัล (WINNING_ENTITLEMENT)
     * กฎข้อ 01 & 04_SPEC ("boundedallocations; orderamount!=collectedcash; winningentitlement!=paid"):
     * 1. ยอดจัดสรรสะสมของ Payment ต้องไม่เกิน payment.amountMinor
     * 2. ยอดจัดสรรสะสมของเป้าหมาย (บิล หรือ สิทธิรางวัล) ต้องไม่เกินยอดเต็มของบิลหรือสิทธิรางวัลนั้น
     */
    @Transaction
    suspend fun allocatePaymentToTargetTransaction(
        allocationId: String,
        paymentId: String,
        targetType: String,
        targetId: String,
        allocationAmountMinor: Long,
        actorId: String,
        nowEpochMs: Long
    ): PaymentAllocationResult {
        if (allocationAmountMinor <= 0L) {
            return PaymentAllocationResult.Rejected("ยอดจัดสรรต้องมากกว่า 0 สตางค์")
        }

        val payment = getPaymentRecordById(paymentId)
            ?: return PaymentAllocationResult.Rejected("ไม่พบรายการรับ/จ่ายเงินสดรหัส $paymentId")

        val newPaymentAllocatedTotal = try {
            ExactMoneyMath.checkedAdd(payment.allocatedTotalMinor, allocationAmountMinor)
        } catch (e: ArithmeticException) {
            return PaymentAllocationResult.Rejected("ยอดจัดสรรรวมเกินขอบเขตตัวเลข 64-bit Long")
        }

        if (newPaymentAllocatedTotal > payment.amountMinor) {
            val remainingPaymentMinor = ExactMoneyMath.checkedSubtract(
                payment.amountMinor,
                payment.allocatedTotalMinor
            )
            return PaymentAllocationResult.Rejected(
                "ยอดจัดสรรเกินวงเงินคงเหลือของรายการชำระเงิน $paymentId (คงเหลือให้จัดสรรได้ $remainingPaymentMinor สตางค์)"
            )
        }

        val existingTargetAllocations = getAllocationsForTarget(targetType, targetId)
        val existingTargetAllocatedMinor = ExactMoneyMath.checkedSum(
            existingTargetAllocations.map { it.allocatedAmountMinor }
        )
        val projectedTargetAllocatedMinor = ExactMoneyMath.checkedAdd(
            existingTargetAllocatedMinor,
            allocationAmountMinor
        )

        val targetMaxCapMinor: Long = when (targetType) {
            TARGET_TYPE_BILL_RECEIVABLE -> {
                if (payment.paymentDirection != PAYMENT_DIRECTION_INFLOW_COLLECTION) {
                    return PaymentAllocationResult.Rejected(
                        "การจัดสรรยอดรับชำระค่าบิลต้องใช้รายการรับเงินสด (INFLOW_COLLECTION) เท่านั้น"
                    )
                }
                val bill = getBillByIdForAllocation(targetId)
                    ?: return PaymentAllocationResult.Rejected("ไม่พบบิลรหัส $targetId สำหรับจัดสรรยอดรับชำระ")
                if (bill.status != BillStatus.ACTIVE.name) {
                    return PaymentAllocationResult.Rejected("ไม่สามารถจัดสรรยอดชำระให้กับบิลที่ถูกยกเลิกแล้ว (${bill.humanBillCode})")
                }
                bill.currentTotalAmountMinor
            }

            TARGET_TYPE_WINNING_ENTITLEMENT -> {
                if (payment.paymentDirection != PAYMENT_DIRECTION_OUTFLOW_ENTITLEMENT) {
                    return PaymentAllocationResult.Rejected(
                        "การจัดสรรยอดจ่ายเงินรางวัลต้องใช้รายการจ่ายเงินสด (OUTFLOW_ENTITLEMENT_PAYOUT) เท่านั้น"
                    )
                }
                val entitlement = getWinningEntitlementByIdForAllocation(targetId)
                    ?: return PaymentAllocationResult.Rejected("ไม่พบรายการสิทธิได้รับรางวัลรหัส $targetId")
                entitlement.entitlementPayoutMinor
            }

            else -> return PaymentAllocationResult.Rejected("ประเภทเป้าหมายการจัดสรรไม่ถูกต้อง ($targetType)")
        }

        if (projectedTargetAllocatedMinor > targetMaxCapMinor) {
            val remainingTargetMinor = ExactMoneyMath.checkedSubtract(
                targetMaxCapMinor,
                existingTargetAllocatedMinor
            )
            return PaymentAllocationResult.Rejected(
                "ยอดจัดสรรเกินยอดคงค้างของเป้าหมาย $targetId (ยอดคงค้างสูงสุดที่จัดสรรได้ $remainingTargetMinor สตางค์)"
            )
        }

        updatePaymentRecord(
            payment.copy(allocatedTotalMinor = newPaymentAllocatedTotal)
        )

        insertPaymentAllocation(
            PaymentAllocationEntity(
                allocationId = allocationId,
                paymentId = paymentId,
                targetType = targetType,
                targetId = targetId,
                allocatedAmountMinor = allocationAmountMinor,
                createdAtEpochMs = nowEpochMs
            )
        )

        insertAuditLog(
            AuditLogEntity(
                eventType = "PAYMENT_ALLOCATED",
                actorRole = actorId,
                roundId = payment.roundId,
                summaryTh = "จัดสรรเงินจาก $paymentId เข้า $targetType ($targetId) จำนวน $allocationAmountMinor สตางค์ (รวมจัดสรรแล้ว $projectedTargetAllocatedMinor / $targetMaxCapMinor สตางค์)",
                createdAtEpochMs = nowEpochMs
            )
        )

        return PaymentAllocationResult.Allocated(
            allocationId = allocationId,
            paymentId = paymentId,
            targetType = targetType,
            targetId = targetId,
            allocatedAmountMinor = allocationAmountMinor,
            newPaymentAllocatedTotalMinor = newPaymentAllocatedTotal,
            newTargetAllocatedTotalMinor = projectedTargetAllocatedMinor
        )
    }

    // =========================================================================
    // Phase 09 & Phase 10 — Counterparties, Contracts, Manual Transfer Batches & Responses
    // =========================================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCounterparty(counterparty: CounterpartyEntity)

    @Query("SELECT * FROM counterparties ORDER BY counterparty_code ASC")
    suspend fun getAllCounterparties(): List<CounterpartyEntity>

    @Query("SELECT * FROM counterparties WHERE counterparty_id = :counterpartyId LIMIT 1")
    suspend fun getCounterpartyById(counterpartyId: String): CounterpartyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCounterpartyContract(contract: CounterpartyContractEntity)

    @Query("SELECT * FROM counterparty_contracts WHERE round_id = :roundId ORDER BY contract_id ASC")
    suspend fun getContractsForRound(roundId: String): List<CounterpartyContractEntity>

    @Query("SELECT * FROM counterparty_contracts WHERE contract_id = :contractId LIMIT 1")
    suspend fun getContractById(contractId: String): CounterpartyContractEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertManualTransferBatch(batch: ManualTransferBatchEntity)

    @Update
    suspend fun updateManualTransferBatch(batch: ManualTransferBatchEntity)

    @Query("SELECT * FROM manual_transfer_batches WHERE batch_id = :batchId LIMIT 1")
    suspend fun getTransferBatchById(batchId: String): ManualTransferBatchEntity?

    @Query("SELECT * FROM manual_transfer_batches WHERE round_id = :roundId ORDER BY created_at_epoch_ms DESC")
    suspend fun getTransferBatchesForRound(roundId: String): List<ManualTransferBatchEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertManualTransferLines(lines: List<ManualTransferLineEntity>)

    @Update
    suspend fun updateManualTransferLine(line: ManualTransferLineEntity)

    @Query("SELECT * FROM manual_transfer_lines WHERE batch_id = :batchId ORDER BY transfer_line_id ASC")
    suspend fun getTransferLinesForBatch(batchId: String): List<ManualTransferLineEntity>

    @Query("SELECT * FROM manual_transfer_lines WHERE round_id = :roundId ORDER BY transfer_line_id ASC")
    suspend fun getTransferLinesForRound(roundId: String): List<ManualTransferLineEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTransferResponse(response: TransferResponseEntity)

    @Query("SELECT * FROM transfer_responses WHERE batch_id = :batchId ORDER BY recorded_at_epoch_ms DESC")
    suspend fun getResponsesForBatch(batchId: String): List<TransferResponseEntity>

    @Query(
        """
        SELECT tr.* FROM transfer_responses tr
        INNER JOIN manual_transfer_batches b ON tr.batch_id = b.batch_id
        WHERE b.round_id = :roundId
        ORDER BY tr.recorded_at_epoch_ms DESC
        """
    )
    suspend fun getResponsesForRound(roundId: String): List<TransferResponseEntity>

    /**
     * สร้างคู่สัญญาและสัญญาการรับความเสี่ยงสาธิตประจำงวด (หากยังไม่มี)
     * กฎข้อ 01 & D09: ปิดการเชื่อมต่อโอนเงิน/ส่งโพยออกภายนอกจริงทั้งหมด ใช้บันทึกด้วยมือในเครื่องเท่านั้น
     */
    @Transaction
    suspend fun ensureDemoCounterpartyAndContractSeeded(
        roundId: String,
        nowEpochMs: Long
    ): Pair<CounterpartyEntity, CounterpartyContractEntity> {
        val existingCp = getCounterpartyById(DEMO_COUNTERPARTY_ID)
        val cp = if (existingCp == null) {
            val created = CounterpartyEntity(
                counterpartyId = DEMO_COUNTERPARTY_ID,
                counterpartyCode = "CP-DEMO-01",
                displayNameTh = "คู่สัญญารับตีออกสาธิต #1 (บันทึกด้วยมือในเครื่องเท่านั้น)",
                isActive = true,
                createdAtEpochMs = nowEpochMs
            )
            upsertCounterparty(created)
            created
        } else {
            existingCp
        }

        val contractId = "CTR-$roundId-DEMO01"
        val existingContract = getContractById(contractId)
        val contract = if (existingContract == null) {
            val created = CounterpartyContractEntity(
                contractId = contractId,
                counterpartyId = cp.counterpartyId,
                roundId = roundId,
                contractTopRateExact = "60",
                contractBottomRateExact = "60",
                maxTransferCapMinor = 500_000L, // 5,000.00 THB demo cap
                createdAtEpochMs = nowEpochMs
            )
            upsertCounterpartyContract(created)
            created
        } else {
            existingContract
        }
        return cp to contract
    }

    /**
     * สร้างชุดรายการตีออกด้วยมือ (Manual Transfer Batch) ในสถานะ PENDING
     * กฎข้อ 01 & 04_SPEC ("Pendingtransfer no reduction"):
     * - รายการที่เพิ่งสร้างอยู่ในสถานะ PENDING มี acceptedAmountMinor = 0 และ acceptedRecoverablePayoutMinor = 0
     * - จึงไม่ลดความเสี่ยง (Net Exposure) จนกว่าจะมีการบันทึกผลตอบรับ ACCEPTED หรือ PARTIAL จากคู่สัญญา
     */
    @Transaction
    suspend fun createManualTransferBatchPendingTransaction(
        batchId: String,
        roundId: String,
        contractId: String,
        draftLines: List<ManualTransferLineDraft>,
        actorId: String,
        nowEpochMs: Long
    ): ManualTransferBatchCreateResult {
        if (draftLines.isEmpty()) {
            return ManualTransferBatchCreateResult.Rejected("ต้องมีรายการเลขตีออกอย่างน้อย 1 รายการ")
        }
        val contract = getContractById(contractId)
            ?: return ManualTransferBatchCreateResult.Rejected("ไม่พบสัญญาคู่สัญญารหัส $contractId")
        if (contract.roundId != roundId) {
            return ManualTransferBatchCreateResult.Rejected("สัญญาคู่สัญญาไม่ได้ผูกกับงวดปัจจุบัน ($roundId)")
        }
        val cp = getCounterpartyById(contract.counterpartyId)
            ?: return ManualTransferBatchCreateResult.Rejected("ไม่พบข้อมูลคู่สัญญาของสัญญานี้")
        if (!cp.isActive) {
            return ManualTransferBatchCreateResult.Rejected("คู่สัญญา ${cp.displayNameTh} ถูกปิดใช้งาน")
        }

        val existingBatches = getTransferBatchesForRound(roundId).filter {
            it.contractId == contractId && it.status != TransferResponseStatus.REJECTED.name
        }
        val alreadySubmittedForContractMinor = ExactMoneyMath.checkedSum(
            existingBatches.map {
                if (it.status == TransferResponseStatus.PENDING.name) it.totalSubmittedMinor else it.totalAcceptedMinor
            }
        )

        val seenKeys = mutableSetOf<Pair<String, BetSide>>()
        var batchSubmittedTotalMinor = 0L
        val lineEntities = mutableListOf<ManualTransferLineEntity>()

        for (draft in draftLines) {
            val validNum = try {
                NumberExpansionEngine.validateTwoDigitNumber(draft.numberTwoDigit)
            } catch (e: IllegalArgumentException) {
                return ManualTransferBatchCreateResult.Rejected("เลข 2 ตัวไม่ถูกต้อง: ${e.message}")
            }
            if (draft.submittedAmountMinor <= 0L) {
                return ManualTransferBatchCreateResult.Rejected("ยอดส่งตีออกต่อเลขต้องมากกว่า 0 สตางค์")
            }
            val key = validNum to draft.side
            if (!seenKeys.add(key)) {
                return ManualTransferBatchCreateResult.Rejected("พบเลขซ้ำในใบตีออกเดียวกัน ($validNum ${draft.side.thaiLabel})")
            }

            batchSubmittedTotalMinor = ExactMoneyMath.checkedAdd(
                batchSubmittedTotalMinor,
                draft.submittedAmountMinor
            )

            val rateExact = when (draft.side) {
                BetSide.TOP -> contract.contractTopRateExact
                BetSide.BOTTOM -> contract.contractBottomRateExact
            }

            lineEntities.add(
                ManualTransferLineEntity(
                    batchId = batchId,
                    roundId = roundId,
                    numberTwoDigit = validNum,
                    side = draft.side.name,
                    submittedAmountMinor = draft.submittedAmountMinor,
                    acceptedAmountMinor = 0L, // PENDING: ยังไม่ถูกรับความเสี่ยง
                    contractRateExact = rateExact,
                    acceptedRecoverablePayoutMinor = 0L, // PENDING: ลดความเสี่ยง = 0
                    lineStatus = TransferResponseStatus.PENDING.name
                )
            )
        }

        val projectedContractMinor = ExactMoneyMath.checkedAdd(
            alreadySubmittedForContractMinor,
            batchSubmittedTotalMinor
        )
        if (projectedContractMinor > contract.maxTransferCapMinor) {
            return ManualTransferBatchCreateResult.Rejected(
                "ยอดส่งตีออกรวมเกินเพดานสัญญา (${ExactMoneyMath.formatMinorToThbString(projectedContractMinor)} > ${ExactMoneyMath.formatMinorToThbString(contract.maxTransferCapMinor)} บาท)"
            )
        }

        val batchEntity = ManualTransferBatchEntity(
            batchId = batchId,
            roundId = roundId,
            contractId = contractId,
            status = TransferResponseStatus.PENDING.name,
            totalSubmittedMinor = batchSubmittedTotalMinor,
            totalAcceptedMinor = 0L,
            createdAtEpochMs = nowEpochMs
        )

        insertManualTransferBatch(batchEntity)
        insertManualTransferLines(lineEntities)
        insertAuditLog(
            AuditLogEntity(
                eventType = "MANUAL_TRANSFER_BATCH_CREATED_PENDING",
                actorRole = actorId,
                roundId = roundId,
                summaryTh = "สร้างใบตีออกด้วยมือ $batchId สถานะ PENDING ยอดส่ง ${ExactMoneyMath.formatMinorToThbString(batchSubmittedTotalMinor)} บาท (ยังไม่ลดความเสี่ยงจนกว่าคู่สัญญาจะตอบรับ)",
                createdAtEpochMs = nowEpochMs
            )
        )

        return ManualTransferBatchCreateResult.CreatedPending(
            batchId = batchId,
            totalSubmittedMinor = batchSubmittedTotalMinor,
            lineCount = lineEntities.size
        )
    }

    /**
     * บันทึกผลตอบรับจากคู่สัญญาด้วยมือ (ACCEPTED / PARTIAL / REJECTED)
     * พร้อมแยกการตั้งหนี้คู่สัญญาในขอบเขตบัญชี COUNTERPARTY_SETTLEMENT ออกจากความเสี่ยง
     * กฎข้อ 01 & 04_SPEC:
     * - ACCEPTED: ยอมรับเต็มจำนวนทุกรายการใน Batch
     * - PARTIAL: ยอมรับตามจำนวน acceptedAmountByLineKey (0 <= accepted <= submitted)
     * - REJECTED: ปฏิเสธทั้งหมด (accepted = 0, recoverable = 0)
     */
    @Transaction
    suspend fun recordManualTransferResponseTransaction(
        responseId: String,
        batchId: String,
        responseStatus: TransferResponseStatus,
        partialAcceptedMinorByKey: Map<Pair<String, BetSide>, Long>,
        manualReferenceNoteTh: String,
        settlementLedgerTxId: String?,
        settlementOperationKey: String?,
        actorId: String,
        nowEpochMs: Long
    ): ManualTransferResponseResult {
        if (responseStatus == TransferResponseStatus.PENDING) {
            return ManualTransferResponseResult.Rejected("ผลตอบรับจากคู่สัญญาต้องเป็น ACCEPTED, PARTIAL หรือ REJECTED เท่านั้น")
        }
        if (manualReferenceNoteTh.isBlank()) {
            return ManualTransferResponseResult.Rejected("ต้องระบุหมายเลขอ้างอิงหรือบันทึกหลักฐานการตอบรับจากคู่สัญญา")
        }

        val batch = getTransferBatchById(batchId)
            ?: return ManualTransferResponseResult.Rejected("ไม่พบใบตีออกรหัส $batchId")

        if (batch.status != TransferResponseStatus.PENDING.name) {
            return ManualTransferResponseResult.Rejected(
                "ใบตีออก $batchId ได้รับการบันทึกผลตอบรับไปแล้ว (สถานะปัจจุบัน: ${batch.status})"
            )
        }

        val lines = getTransferLinesForBatch(batchId)
        if (lines.isEmpty()) {
            return ManualTransferResponseResult.Rejected("ไม่พบบรรทัดรายการในใบตีออก $batchId")
        }

        var totalAcceptedMinor = 0L
        var totalAcceptedRecoverablePayoutMinor = 0L
        val updatedLines = mutableListOf<ManualTransferLineEntity>()

        for (line in lines) {
            val sideEnum = BetSide.valueOf(line.side)
            val key = line.numberTwoDigit to sideEnum

            val acceptedMinor: Long = when (responseStatus) {
                TransferResponseStatus.ACCEPTED -> line.submittedAmountMinor
                TransferResponseStatus.REJECTED -> 0L
                TransferResponseStatus.PARTIAL -> {
                    val requested = partialAcceptedMinorByKey[key] ?: 0L
                    if (requested < 0L || requested > line.submittedAmountMinor) {
                        return ManualTransferResponseResult.Rejected(
                            "ยอดรับบางส่วนของเลข ${line.numberTwoDigit} (${sideEnum.thaiLabel}) ต้องอยู่ระหว่าง 0 ถึง ${line.submittedAmountMinor} สตางค์"
                        )
                    }
                    requested
                }
                TransferResponseStatus.PENDING -> 0L
            }

            val rateBigDecimal = ExactMoneyMath.parseExactRate(line.contractRateExact)
            val recoverablePayoutMinor = if (acceptedMinor > 0L) {
                ExactMoneyMath.computeProvisionalLinePayoutMinor(
                    amountMinor = acceptedMinor,
                    rate = rateBigDecimal
                )
            } else {
                0L
            }

            val resolvedLineStatus = when {
                acceptedMinor == 0L -> TransferResponseStatus.REJECTED.name
                acceptedMinor == line.submittedAmountMinor -> TransferResponseStatus.ACCEPTED.name
                else -> TransferResponseStatus.PARTIAL.name
            }

            totalAcceptedMinor = ExactMoneyMath.checkedAdd(totalAcceptedMinor, acceptedMinor)
            totalAcceptedRecoverablePayoutMinor = ExactMoneyMath.checkedAdd(
                totalAcceptedRecoverablePayoutMinor,
                recoverablePayoutMinor
            )

            updatedLines.add(
                line.copy(
                    acceptedAmountMinor = acceptedMinor,
                    acceptedRecoverablePayoutMinor = recoverablePayoutMinor,
                    lineStatus = resolvedLineStatus
                )
            )
        }

        if (responseStatus == TransferResponseStatus.PARTIAL && totalAcceptedMinor == 0L) {
            return ManualTransferResponseResult.Rejected(
                "สถานะรับบางส่วน (PARTIAL) ต้องมียอดที่คู่สัญญายอมรับมากกว่า 0 สตางค์ (หากปฏิเสธทั้งหมดให้เลือก REJECTED)"
            )
        }

        for (uLine in updatedLines) {
            updateManualTransferLine(uLine)
        }

        updateManualTransferBatch(
            batch.copy(
                status = responseStatus.name,
                totalAcceptedMinor = totalAcceptedMinor
            )
        )

        insertTransferResponse(
            TransferResponseEntity(
                responseId = responseId,
                batchId = batchId,
                responseStatus = responseStatus.name,
                manualReferenceNoteTh = manualReferenceNoteTh.trim(),
                recordedByActorId = actorId,
                recordedAtEpochMs = nowEpochMs
            )
        )

        // แยกการตั้งหนี้ค่าส่งตีออกในขอบเขต COUNTERPARTY_SETTLEMENT เฉพาะยอดที่คู่สัญญายอมรับจริง (totalAcceptedMinor > 0)
        var postedSettlementTxId: String? = null
        if (totalAcceptedMinor > 0L && settlementLedgerTxId != null && settlementOperationKey != null) {
            ensureDemoChartOfAccountsSeeded(nowEpochMs)
            val postResult = postBalancedTransaction(
                transactionId = settlementLedgerTxId,
                operationKey = settlementOperationKey,
                roundId = batch.roundId,
                currencyCode = "THB",
                accountScope = AccountScope.COUNTERPARTY_SETTLEMENT.name,
                reversesTransactionId = null,
                memoTh = "ตั้งภาระผูกพันคู่สัญญาใบตีออก $batchId (${responseStatus.name}): ${manualReferenceNoteTh.trim()}",
                postedByActorId = actorId,
                entries = listOf(
                    LedgerEntryDraft(
                        accountId = DEMO_ACC_COUNTERPARTY_RECEIVABLE,
                        direction = LedgerEntryDirection.DEBIT,
                        amountMinor = totalAcceptedMinor
                    ),
                    LedgerEntryDraft(
                        accountId = DEMO_ACC_COUNTERPARTY_PAYABLE,
                        direction = LedgerEntryDirection.CREDIT,
                        amountMinor = totalAcceptedMinor
                    )
                ),
                nowEpochMs = nowEpochMs
            )
            if (postResult is LedgerPostingResult.Posted) {
                postedSettlementTxId = postResult.transactionId
            }
        }

        insertAuditLog(
            AuditLogEntity(
                eventType = "MANUAL_TRANSFER_RESPONSE_${responseStatus.name}",
                actorRole = actorId,
                roundId = batch.roundId,
                summaryTh = "บันทึกผลตอบรับคู่สัญญาใบตีออก $batchId สถานะ ${responseStatus.name} ยอดรับ ${ExactMoneyMath.formatMinorToThbString(totalAcceptedMinor)} บาท (สิทธิรับคืนสูงสุดรวมตามสัญญา ${ExactMoneyMath.formatMinorToThbString(totalAcceptedRecoverablePayoutMinor)} บาท)",
                createdAtEpochMs = nowEpochMs
            )
        )

        return ManualTransferResponseResult.Recorded(
            responseId = responseId,
            batchId = batchId,
            finalBatchStatus = responseStatus,
            totalSubmittedMinor = batch.totalSubmittedMinor,
            totalAcceptedMinor = totalAcceptedMinor,
            totalAcceptedRecoverablePayoutMinor = totalAcceptedRecoverablePayoutMinor,
            settlementLedgerTransactionId = postedSettlementTxId
        )
    }

    companion object {
        const val DEMO_ACC_ORDER_CASH = "ACC-DEMO-1101"
        const val DEMO_ACC_BILL_RECEIVABLE = "ACC-DEMO-1201"
        const val DEMO_ACC_ENTITLEMENT_PAYABLE = "ACC-DEMO-2101"
        const val DEMO_ACC_ORDER_REVENUE = "ACC-DEMO-4101"
        const val DEMO_ACC_ENTITLEMENT_EXPENSE = "ACC-DEMO-5101"
        const val DEMO_ACC_OPERATING_DRAWER_CASH = "ACC-DEMO-1001"
        const val DEMO_ACC_OPERATING_RESERVE_EQUITY = "ACC-DEMO-3001"
        const val DEMO_ACC_COUNTERPARTY_RECEIVABLE = "ACC-DEMO-1301"
        const val DEMO_ACC_COUNTERPARTY_PAYABLE = "ACC-DEMO-2301"

        const val PAYMENT_DIRECTION_INFLOW_COLLECTION = "INFLOW_COLLECTION"
        const val PAYMENT_DIRECTION_OUTFLOW_ENTITLEMENT = "OUTFLOW_ENTITLEMENT_PAYOUT"

        const val TARGET_TYPE_BILL_RECEIVABLE = "BILL_RECEIVABLE"
        const val TARGET_TYPE_WINNING_ENTITLEMENT = "WINNING_ENTITLEMENT"

        const val DEMO_COUNTERPARTY_ID = "CP-DEMO-001"
    }
}

data class ManualTransferLineDraft(
    val numberTwoDigit: String,
    val side: BetSide,
    val submittedAmountMinor: Long
)

sealed interface ManualTransferBatchCreateResult {
    data class CreatedPending(
        val batchId: String,
        val totalSubmittedMinor: Long,
        val lineCount: Int
    ) : ManualTransferBatchCreateResult

    data class Rejected(
        val reasonTh: String
    ) : ManualTransferBatchCreateResult
}

sealed interface ManualTransferResponseResult {
    data class Recorded(
        val responseId: String,
        val batchId: String,
        val finalBatchStatus: TransferResponseStatus,
        val totalSubmittedMinor: Long,
        val totalAcceptedMinor: Long,
        val totalAcceptedRecoverablePayoutMinor: Long,
        val settlementLedgerTransactionId: String?
    ) : ManualTransferResponseResult

    data class Rejected(
        val reasonTh: String
    ) : ManualTransferResponseResult
}

data class LedgerEntryDraft(
    val accountId: String,
    val direction: LedgerEntryDirection,
    val amountMinor: Long
)

sealed interface LedgerPostingResult {
    data class Posted(
        val transactionId: String,
        val totalBalancedMinor: Long,
        val replayed: Boolean
    ) : LedgerPostingResult

    data class Rejected(
        val reasonTh: String
    ) : LedgerPostingResult
}

sealed interface PaymentRecordResult {
    data class Recorded(
        val paymentId: String,
        val ledgerTransactionId: String,
        val amountMinor: Long
    ) : PaymentRecordResult

    data class Rejected(
        val reasonTh: String
    ) : PaymentRecordResult
}

sealed interface PaymentAllocationResult {
    data class Allocated(
        val allocationId: String,
        val paymentId: String,
        val targetType: String,
        val targetId: String,
        val allocatedAmountMinor: Long,
        val newPaymentAllocatedTotalMinor: Long,
        val newTargetAllocatedTotalMinor: Long
    ) : PaymentAllocationResult

    data class Rejected(
        val reasonTh: String
    ) : PaymentAllocationResult
}
