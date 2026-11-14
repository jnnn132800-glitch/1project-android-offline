package com.oneproject.offline.ui.bills

import com.oneproject.offline.data.local.dao.AtomicBillCommitRequest
import com.oneproject.offline.data.local.dao.AtomicCommitResult
import com.oneproject.offline.data.local.dao.AuthoritativeCommitDao
import com.oneproject.offline.data.local.dao.CartAndQuoteDao
import com.oneproject.offline.data.local.dao.PersistedLineCommitInput
import com.oneproject.offline.data.local.entity.ApprovalRecordEntity
import com.oneproject.offline.data.local.entity.AuditLogEntity
import com.oneproject.offline.data.local.entity.BetSide
import com.oneproject.offline.data.local.entity.BillEntity
import com.oneproject.offline.data.local.entity.BillNumberSideLineEntity
import com.oneproject.offline.data.local.entity.BillRevisionEntity
import com.oneproject.offline.data.local.entity.BillStatus
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
 * ชุดทดสอบสำหรับ Phase 05:
 * - ตั้งค่านโยบายมาตรฐาน / รายเลข / ประวัติ (แยก Inheritance รายช่อง และแยก Apply-All ออกจาก เปิด/ปิดรับ ตาม D07 & 05_UI)
 * - ค้นหาบิล / ดูรายละเอียด / แก้ไขสร้าง Revision โดยไม่นับยอดเก่าซ้ำ (no old-double-count) / ยกเลิกพร้อมเหตุผล
 * - จัดการสถานะงวด (OPEN / PAUSED / CLOSED) และการบันทึก Approved Adjustment สำหรับงวดที่ปิดแล้ว (D08)
 */
class Phase05BillsPolicyAndRoundTest {

    private class FakePhase05RoomBackend : CartAndQuoteDao, AuthoritativeCommitDao {
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
        override suspend fun upsertCart(cart: CartEntity) { carts.value = carts.value + (cart.cartId to cart) }
        override suspend fun insertCartLines(lines: List<CartLineEntity>) {
            for (line in lines) {
                val existing = cartLines.value[line.cartId].orEmpty()
                cartLines.value = cartLines.value + (line.cartId to (existing + line.copy(cartLineId = (existing.size + 1).toLong())))
            }
        }
        override suspend fun updateCartLineAmount(cartId: String, cartLineId: Long, newAmountMinor: Long) {
            val existing = cartLines.value[cartId].orEmpty()
            cartLines.value = cartLines.value + (cartId to existing.map { if (it.cartLineId == cartLineId) it.copy(amountMinor = newAmountMinor) else it })
        }
        override suspend fun deleteCartLine(cartId: String, cartLineId: Long) {
            val existing = cartLines.value[cartId].orEmpty()
            cartLines.value = cartLines.value + (cartId to existing.filterNot { it.cartLineId == cartLineId })
        }
        override suspend fun clearCartLines(cartId: String) { cartLines.value = cartLines.value + (cartId to emptyList()) }
        override suspend fun insertQuoteSnapshot(quote: QuoteSnapshotEntity) { quotes[quote.quoteId] = quote }
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

        override suspend fun getPolicyVersion(roundId: String, policyVersion: Long): PolicyVersionEntity? =
            policies[roundId to policyVersion]
        override suspend fun getPolicyHistoryForRound(roundId: String): List<PolicyVersionEntity> =
            policies.values.filter { it.roundId == roundId }.sortedByDescending { it.policyVersion }
        override suspend fun insertPolicyVersion(policyVersion: PolicyVersionEntity): Long {
            val rowId = (policies.size + 1).toLong()
            policies[policyVersion.roundId to policyVersion.policyVersion] = policyVersion.copy(policyRowId = rowId)
            return rowId
        }

        override suspend fun getNumberPolicyOverride(
            roundId: String,
            policyVersion: Long,
            numberTwoDigit: String,
            side: String
        ): NumberPolicyOverrideEntity? = overrides["$roundId|$policyVersion|$numberTwoDigit|$side"]

        override suspend fun getOverridesForPolicyVersion(
            roundId: String,
            policyVersion: Long
        ): List<NumberPolicyOverrideEntity> =
            overrides.values.filter { it.roundId == roundId && it.policyVersion == policyVersion }
                .sortedWith(compareBy({ it.numberTwoDigit }, { it.side }))

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
        override suspend fun searchBillsInRound(roundId: String, searchQuery: String): List<BillEntity> {
            val q = searchQuery.trim()
            return bills.values.filter { bill ->
                bill.roundId == roundId && (
                    q.isEmpty() ||
                        bill.humanBillCode.contains(q, ignoreCase = true) ||
                        bill.billId.contains(q, ignoreCase = true) ||
                        bill.customerNoteTh.contains(q, ignoreCase = true)
                    )
            }.sortedByDescending { it.createdAtEpochMs }
        }
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

    private lateinit var backend: FakePhase05RoomBackend
    private lateinit var repository: AuthoritativeOrderRepository
    private lateinit var controller: BillsAndPolicyController
    private val roundId = "round-demo-initial-001"
    private var currentClockMs = 1_762_761_600_000L
    private var uuidSeq = 1

    @Before
    fun setUp() = runTest {
        backend = FakePhase05RoomBackend()
        backend.rounds[roundId] = RoundEntity(
            roundId = roundId,
            roundCode = "DEMO-2026-001",
            titleTh = "งวดทดสอบ Phase 05",
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
            defaultReceiveCapMinor = 100_000L, // 1,000.00 THB
            defaultPayoutCapMinor = 6_000_000L,
            roundingMode = "HALF_UP",
            isDemoDefault = true,
            changeReason = "Initial demo policy",
            createdAtEpochMs = currentClockMs
        )
        repository = AuthoritativeOrderRepository(backend, backend)
        controller = BillsAndPolicyController(
            orderRepository = repository,
            clockEpochMsProvider = { currentClockMs++ },
            uuidProvider = { "p05-uuid-${uuidSeq++}" }
        )
        controller.loadRoundAndRefreshAll(roundId)
    }

    @Test
    fun policyDefaultsAndOverrides_preservesExplicitOverridesByDefault_andSeparatesApplyAllFromOpenClose() = runTest {
        // 1. ตั้งค่า Override รายเลข 31 (บน): กำหนดเฉพาะอัตราจ่าย = "50" และ ปิดรับ = true
        // ส่วนเพดานรับปล่อยเป็น Inherit (useExplicitReceiveCap = false -> null)
        controller.updateNumberOverrideForm(
            numberInput = "31",
            side = BetSide.TOP,
            useExplicitRate = true,
            rateInput = "50",
            useExplicitReceiveCap = false,
            useExplicitPayoutCap = false,
            useExplicitClosed = true,
            isClosedForOrder = true,
            reasonInput = "เลขอั้นและปิดรับชั่วคราว"
        )
        assertTrue(controller.submitPerNumberOverride())
        assertEquals(2L, controller.state.value.activePolicyVersion)

        val overrideV2 = controller.state.value.currentOverrides.single()
        assertEquals("50", overrideV2.overrideRateExact)
        assertNull(overrideV2.overrideReceiveCapMinor)
        assertEquals(true, overrideV2.overrideIsClosed)

        // 2. เปลี่ยนค่ามาตรฐาน (Default Policy) เป็นอัตราจ่าย 65 และเพดานรับ 1,500 บาท
        // โดยไม่เลือก Apply-All (applyAllClearRateAndCapOverrides = false) ตามกฎ D07 ("ค่ามาตรฐานไม่ทับoverride")
        controller.updateDefaultPolicyForm(
            topRateInput = "65",
            bottomRateInput = "65",
            receiveCapThbInput = "1500",
            payoutCapThbInput = "60000",
            applyAllClearRateAndCapOverrides = false,
            resetOpenCloseOverrides = false,
            reasonInput = "ปรับค่ามาตรฐานรอบบ่ายโดยคง Override เดิม"
        )
        assertTrue(controller.submitNewDefaultPolicyVersion())
        assertEquals(3L, controller.state.value.activePolicyVersion)
        assertEquals(3, controller.state.value.policyHistory.size) // เก็บประวัติครบทั้งรุ่น 1, 2, 3

        val overrideV3 = controller.state.value.currentOverrides.single()
        assertEquals("50", overrideV3.overrideRateExact) // ไม่ถูกค่ามาตรฐาน 65 ทับ!
        assertEquals(true, overrideV3.overrideIsClosed)

        // 3. ผู้ดูแลเลือก Apply-All อัตราจ่าย/เพดาน (applyAllClearRateAndCapOverrides = true)
        // แต่ไม่เลือก resetOpenCloseOverrides (แยก apply-all ออกจาก เปิด/ปิดรับ ตาม 05_UI)
        controller.updateDefaultPolicyForm(
            topRateInput = "70",
            bottomRateInput = "70",
            receiveCapThbInput = "2000",
            payoutCapThbInput = "80000",
            applyAllClearRateAndCapOverrides = true,
            resetOpenCloseOverrides = false,
            reasonInput = "Apply-All อัตราจ่ายใหม่แต่คงเลขที่ปิดรับไว้"
        )
        assertTrue(controller.submitNewDefaultPolicyVersion())
        assertEquals(4L, controller.state.value.activePolicyVersion)

        val overrideV4 = controller.state.value.currentOverrides.single()
        assertNull(overrideV4.overrideRateExact) // ล้างกลับมาสืบทอด 70 แล้ว
        assertEquals(true, overrideV4.overrideIsClosed) // สถานะปิดรับยังคงอยู่!
    }

    @Test
    fun billsSearch_editRevisionNoOldDoubleCount_andCancelWithReasonPreservingHistory() = runTest {
        // สร้างบิลที่ 1 (other_minor = 60,000 สตางค์ บนเลข 31 TOP) และบิลที่ 2 (old_minor = 20,000 สตางค์ บนเลข 31 TOP)
        backend.insertQuoteSnapshot(
            QuoteSnapshotEntity("q1", "c1", roundId, 1L, "h1", 60_000L, 1, 1, currentClockMs, currentClockMs + 60_000L)
        )
        backend.insertQuoteSnapshot(
            QuoteSnapshotEntity("q2", "c2", roundId, 1L, "h2", 20_000L, 1, 1, currentClockMs, currentClockMs + 60_000L)
        )
        repository.commitNewBillAtomically(
            AtomicBillCommitRequest(
                operationKey = "op-b1",
                payloadSha256 = "h1",
                quoteId = "q1",
                cartIdToClear = null,
                roundId = roundId,
                expectedPolicyVersion = 1L,
                newBillId = "bill-01",
                newRevisionId = "rev-01",
                customerNoteTh = "ลูกค้า ก",
                actorId = "OP-1",
                lines = listOf(PersistedLineCommitInput("31", "TOP", 60_000L, "60", 3_600_000L)),
                nowEpochMs = currentClockMs++
            )
        )
        repository.commitNewBillAtomically(
            AtomicBillCommitRequest(
                operationKey = "op-b2",
                payloadSha256 = "h2",
                quoteId = "q2",
                cartIdToClear = null,
                roundId = roundId,
                expectedPolicyVersion = 1L,
                newBillId = "bill-02",
                newRevisionId = "rev-02-v1",
                customerNoteTh = "ลูกค้า ข เป้าหมายแก้ไข",
                actorId = "OP-1",
                lines = listOf(PersistedLineCommitInput("31", "TOP", 20_000L, "60", 1_200_000L)),
                nowEpochMs = currentClockMs++
            )
        )

        // 1. ค้นหาบิลของ "ลูกค้า ข"
        controller.searchBills("ลูกค้า ข")
        assertEquals(1, controller.state.value.matchingBills.size)
        val targetBillId = controller.state.value.matchingBills.single().billId
        controller.selectBillForInspection(targetBillId)
        assertEquals("DEMO-2026-001-B00002", controller.state.value.selectedBill?.humanBillCode)

        // 2. แก้ไขบิลที่ 2 จาก 200 บาท (20,000 สตางค์) เป็น 300 บาท (30,000 สตางค์) ภายใต้เพดาน 1,000 บาท (100,000 สตางค์)
        // ต้องไม่นับยอดเก่าซ้ำ: 60,000 + 30,000 = 90,000 <= 100,000 (ตรงตาม 07_TEST_FIXTURES edit case)
        controller.updateBillEditInputs(
            replacementPasteText = "31 บน 300",
            editReasonTh = "เพิ่มยอดจาก 200 เป็น 300 บาทตามคำขอ"
        )
        val editResult = controller.submitSelectedBillEditRevision(explicitOperationKey = "op-b2-edit-v2")
        assertTrue(editResult is AtomicCommitResult.Committed)
        assertEquals(2, controller.state.value.selectedBillRevisions.size) // เก็บประวัติครบทั้ง Revision #1 และ #2
        assertEquals(90_000L, repository.getCounterForNumberSide(roundId, "31", "TOP")?.activeReceiveTotalMinor)

        // 3. ยกเลิกบิลที่ 2 พร้อมระบุเหตุผล -> ยอดสะสมของเลข 31 TOP ต้องลดลง 30,000 เหลือ 60,000 สตางค์ และเก็บประวัติ Revision ไว้ครบ
        controller.updateBillCancelReason("ลูกค้าขอยกเลิกบิลที่ 2")
        val cancelResult = controller.submitSelectedBillCancel(explicitOperationKey = "op-b2-cancel")
        assertTrue(cancelResult is AtomicCommitResult.Committed)
        assertEquals(BillStatus.CANCELLED.name, controller.state.value.selectedBill?.status)
        assertNull(controller.state.value.selectedBill?.activeRevisionId)
        assertEquals(2, controller.state.value.selectedBillRevisions.size)
        assertEquals(60_000L, repository.getCounterForNumberSide(roundId, "31", "TOP")?.activeReceiveTotalMinor)
    }

    @Test
    fun roundStatus_pauseBlocksEdits_andClosedRoundRequiresApprovedAdjustment() = runTest {
        // สร้างบิล 1 ใบขณะที่งวด OPEN
        backend.insertQuoteSnapshot(
            QuoteSnapshotEntity("q-r1", "c-r1", roundId, 1L, "h-r1", 10_000L, 1, 1, currentClockMs, currentClockMs + 60_000L)
        )
        repository.commitNewBillAtomically(
            AtomicBillCommitRequest(
                operationKey = "op-r1",
                payloadSha256 = "h-r1",
                quoteId = "q-r1",
                cartIdToClear = null,
                roundId = roundId,
                expectedPolicyVersion = 1L,
                newBillId = "bill-r1",
                newRevisionId = "rev-r1",
                customerNoteTh = "บิลทดสอบสถานะงวด",
                actorId = "OP-1",
                lines = listOf(PersistedLineCommitInput("31", "TOP", 10_000L, "60", 600_000L)),
                nowEpochMs = currentClockMs++
            )
        )
        controller.selectBillForInspection("bill-r1")

        // 1. พักรับรายการชั่วคราว (PAUSE)
        controller.updateRoundTransitionReason("พักรับเพื่อตรวจสอบยอดระหว่างวัน")
        assertTrue(controller.transitionActiveRoundStatus(RoundStatus.PAUSED))
        assertEquals(RoundStatus.PAUSED, controller.state.value.activeRoundStatus)

        // ขณะ PAUSED การแก้ไขบิลปกติต้องถูกบล็อกด้วย ROUND_NOT_OPEN
        controller.updateBillEditInputs(
            replacementPasteText = "31 บน 150",
            editReasonTh = "พยายามแก้ขณะพักรับ"
        )
        val blockedWhilePaused = controller.submitSelectedBillEditRevision()
        assertTrue(blockedWhilePaused is AtomicCommitResult.Rejected)
        assertEquals("ROUND_NOT_OPEN", (blockedWhilePaused as AtomicCommitResult.Rejected).code)

        // 2. เปิดรับต่อ (OPEN) -> แก้ไขบิลได้สำเร็จ
        controller.updateRoundTransitionReason("เปิดรับรายการต่อ")
        assertTrue(controller.transitionActiveRoundStatus(RoundStatus.OPEN))
        val allowedWhenOpen = controller.submitSelectedBillEditRevision()
        assertTrue(allowedWhenOpen is AtomicCommitResult.Committed)

        // 3. ปิดงวดถาวร (CLOSED) -> ห้ามเปิดรับซ้ำ และต้องใช้ Approved Adjustment เท่านั้น (กฎ D08)
        controller.updateRoundTransitionReason("ครบเวลาปิดรับประจำงวด")
        assertTrue(controller.transitionActiveRoundStatus(RoundStatus.CLOSED))
        assertEquals(RoundStatus.CLOSED, controller.state.value.activeRoundStatus)

        controller.updateRoundTransitionReason("พยายามเปิดงวดที่ปิดแล้วซ้ำ")
        assertFalse(controller.transitionActiveRoundStatus(RoundStatus.OPEN))

        controller.updateClosedRoundAdjustmentForm(
            targetBillId = "bill-r1",
            approverActorId = "OWNER-APPROVER-01",
            reasonTh = "อนุมัติรายการปรับปรุงหลังปิดงวดตามหลักฐาน"
        )
        assertTrue(controller.submitClosedRoundApprovedAdjustment())
        assertEquals(1, controller.state.value.roundApprovals.size)
        assertEquals("CLOSED_ROUND_ADJUSTMENT", controller.state.value.roundApprovals.single().targetType)
    }
}
