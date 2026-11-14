package com.oneproject.offline.data.repository

import com.oneproject.offline.data.local.dao.AtomicBillCommitRequest
import com.oneproject.offline.data.local.dao.AtomicBillEditRequest
import com.oneproject.offline.data.local.dao.AtomicCommitResult
import com.oneproject.offline.data.local.dao.AuthoritativeCommitDao
import com.oneproject.offline.data.local.dao.CartAndQuoteDao
import com.oneproject.offline.data.local.dao.CartLineDraft
import com.oneproject.offline.data.local.dao.PersistedLineCommitInput
import com.oneproject.offline.data.local.entity.ApprovalRecordEntity
import com.oneproject.offline.data.local.entity.AuditLogEntity
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit Tests สำหรับตรวจสอบ Room Transaction Protocol, Durable Operations,
 * การกู้คืนหลัง Process Death, การตัดยอด Revision เก่าเวลาแก้ไขบิล (07_TEST_FIXTURES edit case)
 * และการยกเลิกบิลโดยไม่ลบประวัติ (Phase 02)
 */
class DurableOperationAndPersistenceTest {

    private class FakePersistentStorage : CartAndQuoteDao, AuthoritativeCommitDao {
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

        override suspend fun getCartById(cartId: String): CartEntity? = carts.value[cartId]

        override fun observeCartForRound(roundId: String): Flow<CartEntity?> =
            carts.map { map ->
                map.values.filter { it.roundId == roundId }.maxByOrNull { it.updatedAtEpochMs }
            }

        override fun observeCartLines(cartId: String): Flow<List<CartLineEntity>> =
            cartLines.map { map -> map[cartId].orEmpty().sortedBy { it.lineSequence } }

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
                val nextId = (existing.size + 1).toLong()
                cartLines.value = cartLines.value + (line.cartId to (existing + line.copy(cartLineId = nextId)))
            }
        }

        override suspend fun updateCartLineAmount(cartId: String, cartLineId: Long, newAmountMinor: Long) {
            val existing = cartLines.value[cartId].orEmpty()
            cartLines.value = cartLines.value + (cartId to existing.map {
                if (it.cartLineId == cartLineId) it.copy(amountMinor = newAmountMinor) else it
            })
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

        override suspend fun upsertLocalActor(actor: LocalActorEntity) {
            actors[actor.actorId] = actor
        }

        override suspend fun insertApprovalRecord(approval: ApprovalRecordEntity) {
            approvals.add(approval)
        }

        override suspend fun getApprovalsForRound(roundId: String): List<ApprovalRecordEntity> =
            approvals.filter { it.roundId == roundId }.sortedByDescending { it.approvedAtEpochMs }

        override suspend fun getDurableOperation(operationKey: String): DurableOperationEntity? =
            operations[operationKey]

        override suspend fun insertDurableOperation(operation: DurableOperationEntity) {
            operations[operation.operationKey] = operation
        }

        override suspend fun getRoundById(roundId: String): RoundEntity? = rounds[roundId]

        override suspend fun getAllRounds(): List<RoundEntity> =
            rounds.values.sortedByDescending { it.createdAtEpochMs }

        override suspend fun insertRound(round: RoundEntity) {
            rounds[round.roundId] = round
        }

        override suspend fun updateRound(round: RoundEntity) {
            rounds[round.roundId] = round
        }

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

        override suspend fun upsertNumberPolicyOverride(overrideEntity: NumberPolicyOverrideEntity) {
            val key = "${overrideEntity.roundId}|${overrideEntity.policyVersion}|${overrideEntity.numberTwoDigit}|${overrideEntity.side}"
            overrides[key] = overrideEntity
        }

        override suspend fun getQuoteSnapshot(quoteId: String): QuoteSnapshotEntity? = quotes[quoteId]

        override suspend fun getNumberSideCounter(
            roundId: String,
            numberTwoDigit: String,
            side: String
        ): NumberSideCounterEntity? = counters[Triple(roundId, numberTwoDigit, side)]

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

        override suspend fun countBillsInRound(roundId: String): Int =
            bills.values.count { it.roundId == roundId }

        override suspend fun insertBill(bill: BillEntity) {
            bills[bill.billId] = bill
        }

        override suspend fun updateBill(bill: BillEntity) {
            bills[bill.billId] = bill
        }

        override suspend fun insertBillRevision(revision: BillRevisionEntity) {
            revisions[revision.revisionId] = revision
        }

        override suspend fun getBillRevisionById(revisionId: String): BillRevisionEntity? =
            revisions[revisionId]

        override suspend fun getRevisionsForBill(billId: String): List<BillRevisionEntity> =
            revisions.values.filter { it.billId == billId }.sortedBy { it.revisionNumber }

        override suspend fun insertBillLines(lines: List<BillNumberSideLineEntity>) {
            val groupedByRev = lines.groupBy { it.revisionId }
            for ((revId, revLines) in groupedByRev) {
                billLines[revId] = revLines.mapIndexed { i, l -> l.copy(billLineId = (i + 1).toLong()) }
            }
        }

        override suspend fun getLinesForRevision(revisionId: String): List<BillNumberSideLineEntity> =
            billLines[revisionId].orEmpty().sortedBy { it.lineNumber }

        override suspend fun clearCartLinesById(cartId: String) {
            clearCartLines(cartId)
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

        override suspend fun getWinningEntitlementsForBillInRevision(
            resultRevisionId: String,
            billId: String
        ): List<WinningEntitlementEntity> =
            winningEntitlements.filter { it.resultRevisionId == resultRevisionId && it.billId == billId }

        override suspend fun getPaymentAllocationsForTargets(
            targetType: String,
            targetIds: List<String>
        ): List<PaymentAllocationEntity> =
            paymentAllocations.filter { it.targetType == targetType && it.targetId in targetIds }

        override suspend fun insertAuditLog(audit: AuditLogEntity): Long {
            val id = (audits.size + 1).toLong()
            audits.add(audit.copy(auditId = id))
            return id
        }
    }

    private lateinit var storage: FakePersistentStorage
    private lateinit var repository: AuthoritativeOrderRepository
    private val roundId = "round-demo-initial-001"
    private val baseEpochMs = 1_762_761_600_000L

    @Before
    fun setUp() {
        storage = FakePersistentStorage()
        storage.rounds[roundId] = RoundEntity(
            roundId = roundId,
            roundCode = "DEMO-2026-001",
            titleTh = "งวดทดสอบ",
            status = RoundStatus.OPEN.name,
            activePolicyVersion = 1L,
            currencyCode = "THB",
            minorUnitScale = 100,
            isDemoSeed = true,
            createdAtEpochMs = baseEpochMs,
            updatedAtEpochMs = baseEpochMs
        )
        storage.policies[roundId to 1L] = PolicyVersionEntity(
            policyRowId = 1L,
            roundId = roundId,
            policyVersion = 1L,
            defaultTopRateExact = "60",
            defaultBottomRateExact = "60",
            defaultReceiveCapMinor = 100_000L, // 1,000.00 THB ตาม 07_TEST_FIXTURES
            defaultPayoutCapMinor = 6_000_000L,
            roundingMode = "HALF_UP",
            isDemoDefault = true,
            changeReason = "Initial demo policy",
            createdAtEpochMs = baseEpochMs
        )
        repository = AuthoritativeOrderRepository(storage, storage)
    }

    @Test
    fun durableCart_preservesDuplicateLinesWithoutSilentMerge_andSurvivesRestart() = runTest {
        val cartId = "cart-001"
        repository.appendLinesToCart(
            cartId = cartId,
            roundId = roundId,
            customerNoteTh = "ลูกค้าทดสอบ",
            newLines = listOf(
                CartLineDraft("31", "TOP", 10_000L, "MANUAL"),
                CartLineDraft("31", "TOP", 5_000L, "MANUAL") // เลขและด้านซ้ำกัน ห้ามยุบรวมเงียบๆ (D03)
            ),
            nowEpochMs = baseEpochMs
        )

        // จำลองการปิดและเปิดแอปใหม่ (สร้าง Repository ใหม่บน Storage เดิม)
        val restartedRepository = AuthoritativeOrderRepository(storage, storage)
        val persistedLines = restartedRepository.getCartLinesSnapshot(cartId)

        assertEquals(2, persistedLines.size)
        assertEquals(10_000L, persistedLines[0].amountMinor)
        assertEquals(5_000L, persistedLines[1].amountMinor)
    }

    @Test
    fun atomicBillCommit_replaysCompletedOperationKeyBeforeStaleQuoteCheck_andDetectsPayloadConflict() = runTest {
        val quoteId = "quote-001"
        val payloadSha = "sha256-cart01-payload"
        storage.insertQuoteSnapshot(
            QuoteSnapshotEntity(
                quoteId = quoteId,
                cartId = "cart-001",
                roundId = roundId,
                policyVersion = 1L,
                payloadSha256 = payloadSha,
                totalAmountMinor = 35_000L,
                lineCount = 3,
                distinctNumberCount = 2,
                createdAtEpochMs = baseEpochMs,
                expiresAtEpochMs = baseEpochMs + 60_000L
            )
        )

        val request = AtomicBillCommitRequest(
            operationKey = "op-commit-cart01",
            payloadSha256 = payloadSha,
            quoteId = quoteId,
            cartIdToClear = "cart-001",
            roundId = roundId,
            expectedPolicyVersion = 1L,
            newBillId = "bill-uuid-001",
            newRevisionId = "rev-uuid-001",
            customerNoteTh = "CART01",
            actorId = "ACTOR-LOCAL-1",
            lines = listOf(
                PersistedLineCommitInput("31", "TOP", 10_000L, "60", 600_000L),
                PersistedLineCommitInput("31", "BOTTOM", 5_000L, "60", 300_000L),
                PersistedLineCommitInput("27", "BOTTOM", 20_000L, "60", 1_200_000L)
            ),
            nowEpochMs = baseEpochMs + 1_000L
        )

        val firstCommit = repository.commitNewBillAtomically(request)
        assertTrue(firstCommit is AtomicCommitResult.Committed)
        val committed = firstCommit as AtomicCommitResult.Committed
        assertEquals(false, committed.replayedFromDurableOperation)
        assertEquals("DEMO-2026-001-B00001", committed.humanBillCode)

        // จำลอง Process Death แล้วผู้ใช้เปิดแอปกลับมาส่ง operationKey เดิมซ้ำ
        // แม้เวลาจะผ่านไปจน Quote หมดอายุแล้ว และ Policy เปลี่ยนเป็นรุ่น 2 แล้ว
        // ระบบต้อง Replay ผลลัพธ์เดิมที่ทำสำเร็จแล้วก่อนตรวจ Quote หมดอายุ!
        storage.rounds[roundId] = storage.rounds[roundId]!!.copy(activePolicyVersion = 2L)
        val replayAfterExpiry = repository.commitNewBillAtomically(
            request.copy(nowEpochMs = baseEpochMs + 999_999L)
        )
        assertTrue(replayAfterExpiry is AtomicCommitResult.Committed)
        val replayed = replayAfterExpiry as AtomicCommitResult.Committed
        assertTrue(replayed.replayedFromDurableOperation)
        assertEquals("bill-uuid-001", replayed.billId)
        assertEquals("DEMO-2026-001-B00001", replayed.humanBillCode)

        // ตรวจสอบว่ายอดสะสมใน Counter ไม่ถูกบวกซ้ำสองรอบ
        val counter31Top = repository.getCounterForNumberSide(roundId, "31", "TOP")
        assertNotNull(counter31Top)
        assertEquals(10_000L, counter31Top?.activeReceiveTotalMinor)

        // หากใช้ operationKey เดิมแต่ส่ง payloadSha256 ต่างจากเดิม ต้องตีเป็น PayloadConflict ทันที
        val conflictResult = repository.commitNewBillAtomically(
            request.copy(payloadSha256 = "sha256-tampered-payload")
        )
        assertTrue(conflictResult is AtomicCommitResult.PayloadConflict)
    }

    @Test
    fun atomicBillEditRevision_excludesOldActiveRevisionFromCap_matchingFixture07EditCase() = runTest {
        // ข้อมูลทดสอบตาม 07_TEST_FIXTURES.json ("edit"):
        // other_minor = 60000, old_minor = 20000, replacement_minor = 30000, limit_minor = 100000, expected_minor = 90000
        storage.insertQuoteSnapshot(
            QuoteSnapshotEntity(
                quoteId = "quote-other",
                cartId = "cart-a",
                roundId = roundId,
                policyVersion = 1L,
                payloadSha256 = "sha-other",
                totalAmountMinor = 60_000L,
                lineCount = 1,
                distinctNumberCount = 1,
                createdAtEpochMs = baseEpochMs,
                expiresAtEpochMs = baseEpochMs + 60_000L
            )
        )
        storage.insertQuoteSnapshot(
            QuoteSnapshotEntity(
                quoteId = "quote-target",
                cartId = "cart-b",
                roundId = roundId,
                policyVersion = 1L,
                payloadSha256 = "sha-target",
                totalAmountMinor = 20_000L,
                lineCount = 1,
                distinctNumberCount = 1,
                createdAtEpochMs = baseEpochMs,
                expiresAtEpochMs = baseEpochMs + 60_000L
            )
        )

        // 1. บันทึกบิลอื่น (other_minor = 60000)
        val resOther = repository.commitNewBillAtomically(
            AtomicBillCommitRequest(
                operationKey = "op-other-bill",
                payloadSha256 = "sha-other",
                quoteId = "quote-other",
                cartIdToClear = null,
                roundId = roundId,
                expectedPolicyVersion = 1L,
                newBillId = "bill-other",
                newRevisionId = "rev-other-1",
                customerNoteTh = "Other Bill",
                actorId = "ACTOR-1",
                lines = listOf(PersistedLineCommitInput("31", "TOP", 60_000L, "60", 3_600_000L)),
                nowEpochMs = baseEpochMs + 100L
            )
        )
        assertTrue(resOther is AtomicCommitResult.Committed)

        // 2. บันทึกบิลเป้าหมายที่จะแก้ไข (old_minor = 20000) -> ยอดสะสมรวมตอนนี้ = 80000
        val resTarget = repository.commitNewBillAtomically(
            AtomicBillCommitRequest(
                operationKey = "op-target-bill-v1",
                payloadSha256 = "sha-target",
                quoteId = "quote-target",
                cartIdToClear = null,
                roundId = roundId,
                expectedPolicyVersion = 1L,
                newBillId = "bill-target",
                newRevisionId = "rev-target-1",
                customerNoteTh = "Target Bill",
                actorId = "ACTOR-1",
                lines = listOf(PersistedLineCommitInput("31", "TOP", 20_000L, "60", 1_200_000L)),
                nowEpochMs = baseEpochMs + 200L
            )
        )
        assertTrue(resTarget is AtomicCommitResult.Committed)
        assertEquals(80_000L, repository.getCounterForNumberSide(roundId, "31", "TOP")?.activeReceiveTotalMinor)

        // 3. แก้ไขบิลเป้าหมายจาก 20000 เป็น replacement_minor = 30000 ภายใต้เพดาน limit_minor = 100000
        // ต้องได้ expected_minor = 60000 + 30000 = 90000 (ไม่ใช่นับซ้ำ 60000 + 20000 + 30000 = 110000)
        val editResult = repository.commitBillEditRevisionAtomically(
            AtomicBillEditRequest(
                operationKey = "op-target-bill-edit-v2",
                payloadSha256 = "sha-target-edit-30000",
                billId = "bill-target",
                newRevisionId = "rev-target-2",
                expectedPolicyVersion = 1L,
                revisionReasonTh = "แก้ไขยอดตามคำขอลูกค้า",
                actorId = "ACTOR-1",
                replacementLines = listOf(PersistedLineCommitInput("31", "TOP", 30_000L, "60", 1_800_000L)),
                nowEpochMs = baseEpochMs + 300L
            )
        )
        assertTrue(editResult is AtomicCommitResult.Committed)
        val edited = editResult as AtomicCommitResult.Committed
        assertEquals(2, edited.revisionNumber)

        val updatedCounter = repository.getCounterForNumberSide(roundId, "31", "TOP")
        assertEquals(90_000L, updatedCounter?.activeReceiveTotalMinor)

        // ตรวจสอบว่า Revision #1 ยังคงถูกเก็บไว้ในประวัติ แต่ activeRevisionId ชี้ไปที่ Revision #2
        val billSnapshot = repository.getBillWithActiveLines("bill-target")
        assertNotNull(billSnapshot)
        assertEquals("rev-target-2", billSnapshot?.bill?.activeRevisionId)
        assertEquals(2, billSnapshot?.revisions?.size)
        assertEquals(30_000L, billSnapshot?.activeLines?.single()?.amountMinor)
    }

    @Test
    fun cancelBill_requiresReason_withdrawsActiveCounter_andPreservesHistory() = runTest {
        storage.insertQuoteSnapshot(
            QuoteSnapshotEntity(
                quoteId = "quote-cancel",
                cartId = "cart-c",
                roundId = roundId,
                policyVersion = 1L,
                payloadSha256 = "sha-cancel",
                totalAmountMinor = 15_000L,
                lineCount = 1,
                distinctNumberCount = 1,
                createdAtEpochMs = baseEpochMs,
                expiresAtEpochMs = baseEpochMs + 60_000L
            )
        )
        repository.commitNewBillAtomically(
            AtomicBillCommitRequest(
                operationKey = "op-create-for-cancel",
                payloadSha256 = "sha-cancel",
                quoteId = "quote-cancel",
                cartIdToClear = null,
                roundId = roundId,
                expectedPolicyVersion = 1L,
                newBillId = "bill-cancel-01",
                newRevisionId = "rev-cancel-01",
                customerNoteTh = "Cancel test",
                actorId = "ACTOR-1",
                lines = listOf(PersistedLineCommitInput("27", "BOTTOM", 15_000L, "60", 900_000L)),
                nowEpochMs = baseEpochMs + 100L
            )
        )
        assertEquals(15_000L, repository.getCounterForNumberSide(roundId, "27", "BOTTOM")?.activeReceiveTotalMinor)

        // ยกเลิกโดยไม่ระบุเหตุผล ต้องถูกปฏิเสธ
        val missingReason = repository.cancelBillAtomically(
            operationKey = "op-cancel-no-reason",
            payloadSha256 = "sha-cancel-empty",
            billId = "bill-cancel-01",
            cancelReasonTh = "   ",
            actorId = "ACTOR-1",
            nowEpochMs = baseEpochMs + 200L
        )
        assertTrue(missingReason is AtomicCommitResult.Rejected)

        // ยกเลิกพร้อมเหตุผลสำเร็จ ยอดสะสมลดเหลือ 0 แต่ประวัติบิลและ Revision ยังอยู่ครบ
        val validCancel = repository.cancelBillAtomically(
            operationKey = "op-cancel-valid",
            payloadSha256 = "sha-cancel-valid",
            billId = "bill-cancel-01",
            cancelReasonTh = "ลูกค้าขอยกเลิกก่อนปิดงวด",
            actorId = "ACTOR-1",
            nowEpochMs = baseEpochMs + 300L
        )
        assertTrue(validCancel is AtomicCommitResult.Committed)
        assertEquals(0L, repository.getCounterForNumberSide(roundId, "27", "BOTTOM")?.activeReceiveTotalMinor)

        val snapshot = repository.getBillWithActiveLines("bill-cancel-01")
        assertNotNull(snapshot)
        assertEquals(BillStatus.CANCELLED.name, snapshot?.bill?.status)
        assertNull(snapshot?.bill?.activeRevisionId)
        assertEquals(1, snapshot?.revisions?.size)
    }
}
