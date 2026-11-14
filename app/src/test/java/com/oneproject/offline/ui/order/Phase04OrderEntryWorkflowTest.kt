package com.oneproject.offline.ui.order

import com.oneproject.offline.data.local.dao.AtomicCommitResult
import com.oneproject.offline.data.local.dao.AuthoritativeCommitDao
import com.oneproject.offline.data.local.dao.CartAndQuoteDao
import com.oneproject.offline.data.local.entity.ApprovalRecordEntity
import com.oneproject.offline.data.local.entity.AuditLogEntity
import com.oneproject.offline.data.local.entity.BillEntity
import com.oneproject.offline.data.local.entity.BillNumberSideLineEntity
import com.oneproject.offline.data.local.entity.BillRevisionEntity
import com.oneproject.offline.data.local.entity.CartEntity
import com.oneproject.offline.data.local.entity.CartLineEntity
import com.oneproject.offline.data.local.entity.DurableOperationEntity
import com.oneproject.offline.data.local.entity.LocalActorEntity
import com.oneproject.offline.data.local.entity.NumberPolicyOverrideEntity
import com.oneproject.offline.data.local.entity.NumberSideCounterEntity
import com.oneproject.offline.data.local.entity.PaymentAllocationEntity
import com.oneproject.offline.data.local.entity.PolicyVersionEntity
import com.oneproject.offline.data.local.entity.QuoteSnapshotEntity
import com.oneproject.offline.data.local.entity.ResultRevisionEntity
import com.oneproject.offline.data.local.entity.RoundEntity
import com.oneproject.offline.data.local.entity.RoundStatus
import com.oneproject.offline.data.local.entity.WinningEntitlementEntity
import com.oneproject.offline.data.repository.AuthoritativeOrderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ชุดทดสอบสำหรับ Phase 04:
 * - แท็บกรอกเอง (Manual), แท็บตาราง 00–99 (Select All, Clear, Undo, Reverse, รูดหน้า/หลัง/19ประตู, Expanded View), แท็บวางข้อความ (Block Batch)
 * - ตะกร้าในเครื่อง (แก้ยอด, ลบรายบรรทัด, ล้างพร้อมยืนยัน, แยก Distinct Numbers กับ Line Count)
 * - หน้าตรวจทาน (Review), คำเตือนเมื่อ Policy เปลี่ยนก่อนยืนยัน, Atomic Commit ที่ต้องมี committedBillId
 * - การป้องกันการกดซ้ำ (Duplicate Key) และการกู้คืนหลัง Process Death (verifyOrRecoverPreviousOperation)
 */
class Phase04OrderEntryWorkflowTest {

    private class FakeOrderRoomBackend : CartAndQuoteDao, AuthoritativeCommitDao {
        val actors = mutableMapOf<String, LocalActorEntity>()
        val approvals = mutableListOf<ApprovalRecordEntity>()
        val rounds = mutableMapOf<String, RoundEntity>()
        val policies = mutableMapOf<Pair<String, Long>, PolicyVersionEntity>()
        val overrides = mutableMapOf<String, NumberPolicyOverrideEntity>()
        val carts = MutableStateFlow<Map<String, CartEntity>>(emptyMap())
        val cartLines = MutableStateFlow<Map<String, List<CartLineEntity>>>(emptyMap())
        val quotes = mutableMapOf<String, QuoteSnapshotEntity>()
        val operations = mutableMapOf<String, DurableOperationEntity>()
        val bills = mutableMapOf<String, BillEntity>()
        val revisions = mutableMapOf<String, BillRevisionEntity>()
        val billLines = mutableMapOf<String, List<BillNumberSideLineEntity>>()
        val counters = mutableMapOf<Triple<String, String, String>, NumberSideCounterEntity>()
        val resultRevisions = mutableMapOf<String, ResultRevisionEntity>()
        val winningEntitlements = mutableListOf<WinningEntitlementEntity>()
        val paymentAllocations = mutableListOf<PaymentAllocationEntity>()
        val audits = mutableListOf<AuditLogEntity>()
        private var nextCartLineId = 1L
        private var nextBillLineId = 1L

        override suspend fun getCartById(cartId: String): CartEntity? = carts.value[cartId]

        override fun observeCartForRound(roundId: String): Flow<CartEntity?> =
            carts.map { m -> m.values.filter { it.roundId == roundId }.maxByOrNull { it.updatedAtEpochMs } }

        override fun observeCartLines(cartId: String): Flow<List<CartLineEntity>> =
            cartLines.map { m -> m[cartId].orEmpty().sortedBy { it.lineSequence } }

        override suspend fun getCartLines(cartId: String): List<CartLineEntity> =
            cartLines.value[cartId].orEmpty().sortedBy { it.lineSequence }

        override suspend fun getMaxLineSequence(cartId: String): Int =
            cartLines.value[cartId].orEmpty().maxOfOrNull { it.lineSequence } ?: 0

        override suspend fun upsertCart(cart: CartEntity) {
            carts.value = carts.value + (cart.cartId to cart)
        }

        override suspend fun insertCartLines(lines: List<CartLineEntity>) {
            for (line in lines) {
                val existing = cartLines.value[line.cartId].orEmpty()
                val assigned = line.copy(cartLineId = nextCartLineId++)
                cartLines.value = cartLines.value + (line.cartId to (existing + assigned))
            }
        }

        override suspend fun updateCartLineAmount(cartId: String, cartLineId: Long, newAmountMinor: Long) {
            val existing = cartLines.value[cartId].orEmpty()
            val updated = existing.map {
                if (it.cartLineId == cartLineId) it.copy(amountMinor = newAmountMinor) else it
            }
            cartLines.value = cartLines.value + (cartId to updated)
        }

        override suspend fun deleteCartLine(cartId: String, cartLineId: Long) {
            val existing = cartLines.value[cartId].orEmpty()
            cartLines.value = cartLines.value + (cartId to existing.filterNot { it.cartLineId == cartLineId })
        }

        override suspend fun clearCartLines(cartId: String) {
            cartLines.value = cartLines.value + (cartId to emptyList())
        }

        override suspend fun insertQuoteSnapshot(quote: QuoteSnapshotEntity) {
            quotes[quote.quoteId] = quote
        }

        override suspend fun getQuoteSnapshotById(quoteId: String): QuoteSnapshotEntity? = quotes[quoteId]

        override suspend fun getLocalActorById(actorId: String): LocalActorEntity? = actors[actorId]
        override suspend fun upsertLocalActor(actor: LocalActorEntity) { actors[actor.actorId] = actor }
        override suspend fun insertApprovalRecord(approval: ApprovalRecordEntity) { approvals.add(approval) }
        override suspend fun getApprovalsForRound(roundId: String): List<ApprovalRecordEntity> =
            approvals.filter { it.roundId == roundId }.sortedByDescending { it.approvedAtEpochMs }
        override suspend fun getDurableOperation(operationKey: String): DurableOperationEntity? = operations[operationKey]
        override suspend fun insertDurableOperation(operation: DurableOperationEntity) { operations[operation.operationKey] = operation }
        override suspend fun getRoundById(roundId: String): RoundEntity? = rounds[roundId]
        override suspend fun getAllRounds(): List<RoundEntity> = rounds.values.sortedByDescending { it.createdAtEpochMs }
        override suspend fun insertRound(round: RoundEntity) { rounds[round.roundId] = round }
        override suspend fun updateRound(round: RoundEntity) { rounds[round.roundId] = round }
        override suspend fun getPolicyVersion(roundId: String, policyVersion: Long): PolicyVersionEntity? = policies[roundId to policyVersion]
        override suspend fun getPolicyHistoryForRound(roundId: String): List<PolicyVersionEntity> =
            policies.values.filter { it.roundId == roundId }.sortedByDescending { it.policyVersion }
        override suspend fun insertPolicyVersion(policyVersion: PolicyVersionEntity): Long {
            val rowId = (policies.size + 1).toLong()
            policies[policyVersion.roundId to policyVersion.policyVersion] = policyVersion.copy(policyRowId = rowId)
            return rowId
        }
        override suspend fun getNumberPolicyOverride(roundId: String, policyVersion: Long, numberTwoDigit: String, side: String): NumberPolicyOverrideEntity? =
            overrides["$roundId|$policyVersion|$numberTwoDigit|$side"]
        override suspend fun getOverridesForPolicyVersion(roundId: String, policyVersion: Long): List<NumberPolicyOverrideEntity> =
            overrides.values.filter { it.roundId == roundId && it.policyVersion == policyVersion }
        override suspend fun upsertNumberPolicyOverride(overrideEntity: NumberPolicyOverrideEntity) {
            overrides["${overrideEntity.roundId}|${overrideEntity.policyVersion}|${overrideEntity.numberTwoDigit}|${overrideEntity.side}"] = overrideEntity
        }
        override suspend fun getQuoteSnapshot(quoteId: String): QuoteSnapshotEntity? = quotes[quoteId]
        override suspend fun getNumberSideCounter(roundId: String, numberTwoDigit: String, side: String): NumberSideCounterEntity? =
            counters[Triple(roundId, numberTwoDigit, side)]
        override suspend fun getAllCountersForRound(roundId: String): List<NumberSideCounterEntity> =
            counters.values.filter { it.roundId == roundId }
        override suspend fun upsertNumberSideCounter(counter: NumberSideCounterEntity) {
            counters[Triple(counter.roundId, counter.numberTwoDigit, counter.side)] = counter
        }
        override suspend fun getBillById(billId: String): BillEntity? = bills[billId]
        override suspend fun getBillByHumanCode(humanBillCode: String): BillEntity? =
            bills.values.firstOrNull { it.humanBillCode == humanBillCode }
        override suspend fun searchBillsInRound(roundId: String, searchQuery: String): List<BillEntity> =
            bills.values.filter { it.roundId == roundId }.sortedByDescending { it.createdAtEpochMs }
        override suspend fun countBillsInRound(roundId: String): Int = bills.values.count { it.roundId == roundId }
        override suspend fun insertBill(bill: BillEntity) { bills[bill.billId] = bill }
        override suspend fun updateBill(bill: BillEntity) { bills[bill.billId] = bill }
        override suspend fun insertBillRevision(revision: BillRevisionEntity) { revisions[revision.revisionId] = revision }
        override suspend fun getBillRevisionById(revisionId: String): BillRevisionEntity? = revisions[revisionId]
        override suspend fun getRevisionsForBill(billId: String): List<BillRevisionEntity> =
            revisions.values.filter { it.billId == billId }.sortedBy { it.revisionNumber }
        override suspend fun insertBillLines(lines: List<BillNumberSideLineEntity>) {
            for ((revId, revLines) in lines.groupBy { it.revisionId }) {
                billLines[revId] = revLines.map { l -> l.copy(lineId = nextBillLineId++) }
            }
        }
        override suspend fun getLinesForRevision(revisionId: String): List<BillNumberSideLineEntity> =
            billLines[revisionId].orEmpty().sortedBy { it.lineNumber }
        override suspend fun clearCartLinesById(cartId: String) { clearCartLines(cartId) }
        override suspend fun insertAuditLog(audit: AuditLogEntity): Long {
            val id = (audits.size + 1).toLong()
            audits.add(audit.copy(auditId = id))
            return id
        }
        override suspend fun getResultRevisionsForRound(roundId: String): List<ResultRevisionEntity> =
            resultRevisions.values.filter { it.roundId == roundId }.sortedByDescending { it.revisionNumber }
        override suspend fun getActiveApprovedResultRevision(roundId: String): ResultRevisionEntity? =
            resultRevisions.values.filter { it.roundId == roundId && it.status == "APPROVED" }.maxByOrNull { it.revisionNumber }
        override suspend fun getResultRevisionById(resultRevisionId: String): ResultRevisionEntity? =
            resultRevisions[resultRevisionId]
        override suspend fun insertResultRevision(resultRevision: ResultRevisionEntity) {
            resultRevisions[resultRevision.resultRevisionId] = resultRevision
        }
        override suspend fun updateResultRevision(resultRevision: ResultRevisionEntity) {
            resultRevisions[resultRevision.resultRevisionId] = resultRevision
        }
        override suspend fun insertWinningEntitlements(entitlements: List<WinningEntitlementEntity>) {
            winningEntitlements.addAll(entitlements)
        }
        override suspend fun getWinningEntitlementsForRevision(resultRevisionId: String): List<WinningEntitlementEntity> =
            winningEntitlements.filter { it.resultRevisionId == resultRevisionId }
        override suspend fun getWinningEntitlementsForBillInRevision(resultRevisionId: String, billId: String): List<WinningEntitlementEntity> =
            winningEntitlements.filter { it.resultRevisionId == resultRevisionId && it.billId == billId }
        override suspend fun getPaymentAllocationsForTargets(targetType: String, targetIds: List<String>): List<PaymentAllocationEntity> =
            paymentAllocations.filter { it.targetType == targetType && it.targetId in targetIds }
    }

    private lateinit var backend: FakeOrderRoomBackend
    private lateinit var repository: AuthoritativeOrderRepository
    private lateinit var controller: OrderEntryWorkflowController
    private val roundId = "round-demo-initial-001"
    private var currentClockMs = 1_762_761_600_000L
    private var uuidSeq = 1

    @Before
    fun setUp() {
        backend = FakeOrderRoomBackend()
        backend.rounds[roundId] = RoundEntity(
            roundId = roundId,
            roundCode = "DEMO-2026-001",
            titleTh = "งวดทดสอบ Phase 04",
            status = RoundStatus.OPEN.name,
            activePolicyVersion = 1L,
            currencyCode = "THB",
            minorUnitScale = 100,
            isDemoSeed = true,
            createdAtEpochMs = currentClockMs,
            updatedAtEpochMs = currentClockMs
        )
        backend.policies[roundId to 1L] = PolicyVersionEntity(
            policyRowId = 1L,
            roundId = roundId,
            policyVersion = 1L,
            defaultTopRateExact = "60",
            defaultBottomRateExact = "60",
            defaultReceiveCapMinor = 100_000L,
            defaultPayoutCapMinor = 6_000_000L,
            roundingMode = "HALF_UP",
            isDemoDefault = true,
            changeReason = "Demo v1",
            createdAtEpochMs = currentClockMs
        )
        repository = AuthoritativeOrderRepository(backend, backend)
        controller = OrderEntryWorkflowController(
            orderRepository = repository,
            clockEpochMsProvider = { currentClockMs },
            uuidProvider = { "uuid-${uuidSeq++}" }
        )
        controller.syncRoundAndPolicyHeader(
            roundId = roundId,
            roundCode = "DEMO-2026-001",
            policyVersion = 1L,
            topRateExact = "60",
            bottomRateExact = "60"
        )
    }

    @Test
    fun grid10x10_supportsSelectAll_clear_undo_door19_reverseUnion_andExpandedView() = runTest {
        // เลือก 31 และ 11 แล้วกดกลับเลข -> ต้องได้ ["11", "13", "31"] ตาม 07_TEST_FIXTURES
        controller.toggleGridNumber("31")
        controller.toggleGridNumber("11")
        assertEquals(setOf("11", "31"), controller.state.value.gridSelectedNumbers)

        controller.gridApplyReverseUnionOnSelected()
        assertEquals(setOf("11", "13", "31"), controller.state.value.gridSelectedNumbers)

        // ทดสอบ Undo ย้อนกลับเป็น ["11", "31"]
        assertTrue(controller.gridUndoLastSelectionChange())
        assertEquals(setOf("11", "31"), controller.state.value.gridSelectedNumbers)

        // ทดสอบ ล้าง และ 19 ประตู สำหรับหลัก "1" -> 19 เลข
        controller.gridClearSelection()
        assertTrue(controller.state.value.gridSelectedNumbers.isEmpty())

        controller.gridSetHelperDigit("1")
        controller.gridApplyDoor19Digit()
        assertEquals(19, controller.state.value.gridSelectedNumbers.size)

        // ทดสอบ Expanded View สำหรับจอแคบ และเลือกทั้งหมด 100 เลข
        controller.setGridExpandedForNarrowScreen(true)
        assertTrue(controller.state.value.isGridExpandedForNarrowScreen)
        controller.gridSelectAll100()
        assertEquals(100, controller.state.value.gridSelectedNumbers.size)
    }

    @Test
    fun pasteBatch_blocksEntireBatchOnSingleError_andCartSupportsEditDeleteClear_withDistinctCount() = runTest {
        // 1. วางข้อความที่มีบรรทัดผิด 1 บรรทัด -> ต้องบล็อกทั้งชุด ตะกร้าต้องยังว่างเปล่า
        controller.updatePasteText(
            """
            31 บน 100
            31 ล่าง -50
            27 ล่าง 200
            """.trimIndent()
        )
        assertFalse(controller.submitPasteBatchToCart())
        assertEquals(0, controller.state.value.cartLineCount)
        assertEquals(1, controller.state.value.pasteErrors.size)

        // 2. วางข้อความที่ถูกต้องตาม CART01 (31 บน 100, 31 ล่าง 50, 27 ล่าง 200)
        controller.updatePasteText(
            """
            31 บน 100
            31 ล่าง 50
            27 ล่าง 200
            """.trimIndent()
        )
        assertTrue(controller.submitPasteBatchToCart())

        // ตรวจสอบการแยกแสดง Distinct Numbers (2 เลข: 31, 27) กับ Line Count (3 รายการ) และยอดรวม 35,000 สตางค์
        assertEquals(2, controller.state.value.cartDistinctNumberCount)
        assertEquals(3, controller.state.value.cartLineCount)
        assertEquals(35_000L, controller.state.value.cartTotalAmountMinor)

        // 3. แก้ไขยอดบรรทัดที่ 2 จาก 50 บาท (5,000 สตางค์) เป็น 100 บาท (10,000 สตางค์) -> ยอดรวมเป็น 40,000 สตางค์
        val secondLineId = controller.state.value.cartLines[1].cartLineId
        assertTrue(controller.editCartLineAmountThb(secondLineId, "100"))
        assertEquals(40_000L, controller.state.value.cartTotalAmountMinor)

        // 4. ลบบรรทัดที่ 2 ออก -> เหลือ 2 รายการ ยอดรวม 30,000 สตางค์
        controller.removeCartLine(secondLineId)
        assertEquals(2, controller.state.value.cartLineCount)
        assertEquals(30_000L, controller.state.value.cartTotalAmountMinor)

        // 5. ล้างตะกร้าพร้อมยืนยัน
        controller.requestClearCartConfirmation(true)
        assertTrue(controller.state.value.showClearCartConfirmDialog)
        controller.confirmClearAllCartLines()
        assertFalse(controller.state.value.showClearCartConfirmDialog)
        assertEquals(0, controller.state.value.cartLineCount)
    }

    @Test
    fun reviewAndAtomicCommit_detectsPolicyChange_commitsWithBillId_andRecoversAfterProcessDeathWithoutDoubleCount() = runTest {
        // เตรียมตะกร้าตาม CART01
        controller.updatePasteText(
            """
            31 บน 100
            31 ล่าง 50
            27 ล่าง 200
            """.trimIndent()
        )
        assertTrue(controller.submitPasteBatchToCart())

        // 1. กดตรวจทาน (Review) ผูกกับ Policy รุ่น #1
        assertTrue(controller.prepareReviewQuote(explicitOperationKey = "op-phase04-001"))
        val initialReview = controller.state.value.activeReviewQuote
        assertNotNull(initialReview)
        assertEquals(1L, initialReview?.boundPolicyVersion)
        assertNull(controller.state.value.committedReceipt)

        // 2. สมมติผู้ดูแลเปลี่ยน Policy ของงวดเป็นรุ่น #2 ระหว่างที่ผู้ใช้อยู่หน้า Review
        backend.policies[roundId to 2L] = PolicyVersionEntity(
            policyRowId = 2L,
            roundId = roundId,
            policyVersion = 2L,
            defaultTopRateExact = "55",
            defaultBottomRateExact = "55",
            defaultReceiveCapMinor = 100_000L,
            defaultPayoutCapMinor = 6_000_000L,
            roundingMode = "HALF_UP",
            isDemoDefault = false,
            changeReason = "ปรับอัตราจ่ายเป็น 55",
            createdAtEpochMs = currentClockMs + 100L
        )
        backend.rounds[roundId] = backend.rounds[roundId]!!.copy(activePolicyVersion = 2L)

        // หากฝืนกดยืนยันด้วยใบตรวจทานรุ่น #1 ระบบใน Room Transaction จะบล็อกด้วย POLICY_CHANGED_FORCE_REVIEW
        val rejectedStalePolicy = controller.confirmReviewAndCommitLocalBill()
        assertTrue(rejectedStalePolicy is AtomicCommitResult.Rejected)
        assertEquals("POLICY_CHANGED_FORCE_REVIEW", (rejectedStalePolicy as AtomicCommitResult.Rejected).code)
        assertTrue(controller.state.value.hasPolicyChangedDuringReview)
        assertNull(controller.state.value.committedReceipt)

        // 3. ผู้ใช้กดคำนวณใบตรวจทานใหม่ตามนโยบายรุ่น #2 แล้วกดยืนยันบันทึกบิล
        assertTrue(controller.prepareReviewQuote(explicitOperationKey = "op-phase04-v2-commit"))
        assertFalse(controller.state.value.hasPolicyChangedDuringReview)

        val commitSuccess = controller.confirmReviewAndCommitLocalBill()
        assertTrue(commitSuccess is AtomicCommitResult.Committed)
        val receipt = controller.state.value.committedReceipt
        assertNotNull(receipt)
        assertTrue(receipt!!.committedBillId.isNotBlank())
        assertEquals("DEMO-2026-001-B00001", receipt.humanBillCode)
        assertEquals("op-phase04-v2-commit", receipt.operationKey)
        assertFalse(receipt.replayedFromDurableOperation)
        assertEquals(0, controller.state.value.cartLineCount)

        // 4. จำลอง Process Death (สร้าง Controller ใหม่เหมือนเปิดแอปใหม่หลังเครื่องดับ)
        // แล้วผู้ใช้กดตรวจสอบ Operation เดิม ("op-phase04-v2-commit")
        val controllerAfterProcessDeath = OrderEntryWorkflowController(
            orderRepository = repository,
            clockEpochMsProvider = { currentClockMs + 50_000L }
        )
        val recovered = controllerAfterProcessDeath.verifyOrRecoverPreviousOperation("op-phase04-v2-commit")
        assertTrue(recovered)
        val recoveredReceipt = controllerAfterProcessDeath.state.value.committedReceipt
        assertNotNull(recoveredReceipt)
        assertEquals(receipt.committedBillId, recoveredReceipt?.committedBillId)
        assertEquals("DEMO-2026-001-B00001", recoveredReceipt?.humanBillCode)
        assertTrue(recoveredReceipt?.replayedFromDurableOperation == true)

        // ตรวจสอบว่ายอดสะสมใน Counter ไม่ถูกตัดซ้ำสองรอบ
        assertEquals(10_000L, repository.getCounterForNumberSide(roundId, "31", "TOP")?.activeReceiveTotalMinor)
        assertEquals(5_000L, repository.getCounterForNumberSide(roundId, "31", "BOTTOM")?.activeReceiveTotalMinor)
        assertEquals(20_000L, repository.getCounterForNumberSide(roundId, "27", "BOTTOM")?.activeReceiveTotalMinor)
    }
}
