package com.oneproject.offline.ui.reports

import com.oneproject.offline.data.local.dao.ApproveResultRevisionRequest
import com.oneproject.offline.data.local.dao.AtomicBillCommitRequest
import com.oneproject.offline.data.local.dao.AtomicCommitResult
import com.oneproject.offline.data.local.dao.AuthoritativeCommitDao
import com.oneproject.offline.data.local.dao.BootstrapDao
import com.oneproject.offline.data.local.dao.CartAndQuoteDao
import com.oneproject.offline.data.local.dao.PersistedLineCommitInput
import com.oneproject.offline.data.local.dao.ResultApprovalOutcome
import com.oneproject.offline.data.local.dao.ResultEntitlementPreviewOutcome
import com.oneproject.offline.data.local.entity.ApprovalRecordEntity
import com.oneproject.offline.data.local.entity.AuditLogEntity
import com.oneproject.offline.data.local.entity.BackupMetadataEntity
import com.oneproject.offline.data.local.entity.BetSide
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
import com.oneproject.offline.data.local.entity.ResultRevisionStatus
import com.oneproject.offline.data.local.entity.RoundEntity
import com.oneproject.offline.data.local.entity.RoundStatus
import com.oneproject.offline.data.local.entity.WinningEntitlementEntity
import com.oneproject.offline.data.repository.AuthoritativeOrderRepository
import com.oneproject.offline.data.repository.OfflineBootstrapRepository
import com.oneproject.offline.domain.engine.PasteBatchParserAndQuoteHasher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ชุดทดสอบสำหรับ Phase 07 (Result Revision Approval, Idempotent Winning Entitlement & Reports Drilldown)
 * ครอบคลุมตามคำสั่ง Phase 07 และ 07_TEST_FIXTURES.json ("CART01" -> top31_bottom27_payout_minor = 1800000):
 * 1. บังคับ Preview ก่อน Approve เสมอ และตัดบิลที่สถานะ CANCELLED ออกจากการคำนวณสิทธิได้รับรางวัล
 * 2. ทดสอบ CART01 (31 บน 100.00, 31 ล่าง 50.00, 27 ล่าง 200.00 @ อัตรา 60): เมื่อผลออก บน=31, ล่าง=27 ต้องได้สิทธิได้รับรางวัล = 1,800,000 สตางค์ (18,000.00 บาท)
 * 3. ทดสอบ Idempotency ทั้งกรณีส่ง operation_key เดิมซ้ำ, กรณีส่งเลขผลรางวัลชุดเดิมซ้ำด้วย operation_key ใหม่, และกรณี PayloadConflict
 * 4. ทดสอบการแก้ไขผลรางวัลด้วย Result Revision (รุ่นเดิมถูกเปลี่ยนเป็น SUPERSEDED และเก็บประวัติครบถ้วน)
 * 5. ยืนยันว่าสิทธิได้รับรางวัล (Winning Entitlement) ไม่ถือว่าจ่ายเงินสดจริง (allocatedPaidCashMinor == 0, unpaidEntitlementBalanceMinor == entitlementPayoutMinor)
 * 6. ทดสอบการเจาะลึกรายบิล/บรรทัด (Bill Drilldown) และเจาะลึกรายเลข 00–99 (Number-Side Drilldown)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Phase07ResultEntitlementAndReportsTest {

    private class FakePhase07Backend : BootstrapDao, CartAndQuoteDao, AuthoritativeCommitDao {
        val roundsFlow = MutableStateFlow<Map<String, RoundEntity>>(emptyMap())
        val policies = mutableMapOf<Pair<String, Long>, PolicyVersionEntity>()
        val actors = mutableMapOf<String, LocalActorEntity>()
        val approvals = mutableListOf<ApprovalRecordEntity>()
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

        override suspend fun countRounds(): Int = roundsFlow.value.size
        override fun observeLatestRound(): Flow<RoundEntity?> =
            roundsFlow.map { m -> m.values.maxByOrNull { it.createdAtEpochMs } }
        override fun observePolicyVersion(roundId: String, policyVersion: Long): Flow<PolicyVersionEntity?> =
            MutableStateFlow(policies[roundId to policyVersion])
        override fun observeAuditCount(): Flow<Int> = MutableStateFlow(audits.size)
        override fun observeBackupHistory(): Flow<List<BackupMetadataEntity>> = MutableStateFlow(emptyList())
        override suspend fun getBackupHistory(): List<BackupMetadataEntity> = emptyList()
        override suspend fun insertBackupMetadata(metadata: BackupMetadataEntity) {}

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
        override suspend fun insertCartLines(lines: List<CartLineEntity>) {}
        override suspend fun updateCartLineAmount(cartId: String, cartLineId: Long, newAmountMinor: Long) {}
        override suspend fun deleteCartLine(cartId: String, cartLineId: Long) {}
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

        override suspend fun getRoundById(roundId: String): RoundEntity? = roundsFlow.value[roundId]
        override suspend fun getAllRounds(): List<RoundEntity> = roundsFlow.value.values.sortedByDescending { it.createdAtEpochMs }
        override suspend fun insertRound(round: RoundEntity) { roundsFlow.value = roundsFlow.value + (round.roundId to round) }
        override suspend fun updateRound(round: RoundEntity) { roundsFlow.value = roundsFlow.value + (round.roundId to round) }

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
            overrides["${overrideEntity.roundId}|${overrideEntity.policyVersion}|${overrideEntity.numberTwoDigit}|${overrideEntity.side}"] = overrideEntity
        }

        override suspend fun getQuoteSnapshot(quoteId: String): QuoteSnapshotEntity? = quotes[quoteId]
        override suspend fun getNumberSideCounter(roundId: String, numberTwoDigit: String, side: String): NumberSideCounterEntity? =
            counters[Triple(roundId, numberTwoDigit, side)]
        override suspend fun getAllCountersForRound(roundId: String): List<NumberSideCounterEntity> =
            counters.values.filter { it.roundId == roundId }
                .sortedWith(compareBy({ it.numberTwoDigit }, { it.side }))
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
                .sortedWith(compareBy({ it.billId }, { it.billLineId }))
        override suspend fun getWinningEntitlementsForBillInRevision(resultRevisionId: String, billId: String): List<WinningEntitlementEntity> =
            winningEntitlements.filter { it.resultRevisionId == resultRevisionId && it.billId == billId }
                .sortedBy { it.billLineId }
        override suspend fun getPaymentAllocationsForTargets(targetType: String, targetIds: List<String>): List<PaymentAllocationEntity> =
            paymentAllocations.filter { it.targetType == targetType && it.targetId in targetIds }
    }

    private lateinit var backend: FakePhase07Backend
    private lateinit var bootstrapRepository: OfflineBootstrapRepository
    private lateinit var orderRepository: AuthoritativeOrderRepository
    private val roundId = "round-demo-initial-001"
    private var currentClockMs = 1_765_100_000_000L

    @Before
    fun setUp() = runTest {
        backend = FakePhase07Backend()
        backend.insertRound(
            RoundEntity(
                roundId = roundId,
                roundCode = "DEMO-2026-001",
                titleTh = "งวดทดสอบ Phase 07",
                status = RoundStatus.OPEN.name,
                activePolicyVersion = 1L,
                currencyCode = "THB",
                minorUnitScale = 100,
                isDemoSeed = true,
                createdAtEpochMs = currentClockMs,
                updatedAtEpochMs = currentClockMs
            )
        )
        backend.insertPolicyVersion(
            PolicyVersionEntity(
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
        )

        bootstrapRepository = OfflineBootstrapRepository(backend)
        orderRepository = AuthoritativeOrderRepository(backend, backend)

        // สร้างบิลที่ 1 ตามชุดข้อมูล CART01 ใน 07_TEST_FIXTURES.json:
        // 31 TOP 10000 @ 60 (payout 600,000), 31 BOTTOM 5000 @ 60 (payout 300,000), 27 BOTTOM 20000 @ 60 (payout 1,200,000)
        val cart01Lines = listOf(
            PersistedLineCommitInput("31", BetSide.TOP.name, 10_000L, "60", 600_000L),
            PersistedLineCommitInput("31", BetSide.BOTTOM.name, 5_000L, "60", 300_000L),
            PersistedLineCommitInput("27", BetSide.BOTTOM.name, 20_000L, "60", 1_200_000L)
        )
        backend.insertQuoteSnapshot(
            QuoteSnapshotEntity(
                quoteId = "q-cart01",
                roundId = roundId,
                policyVersion = 1L,
                payloadSha256 = "hash-cart01",
                totalAmountMinor = 35_000L,
                totalProvisionalPayoutMinor = 2_100_000L,
                lineCount = 3,
                distinctNumberCount = 2,
                createdAtEpochMs = currentClockMs,
                expiresAtEpochMs = currentClockMs + 300_000L
            )
        )
        val commit1 = orderRepository.commitNewBillAtomically(
            AtomicBillCommitRequest(
                operationKey = "op-commit-cart01",
                payloadSha256 = "hash-cart01",
                quoteId = "q-cart01",
                cartIdToClear = null,
                roundId = roundId,
                expectedPolicyVersion = 1L,
                newBillId = "bill-cart01",
                newRevisionId = "rev-cart01-1",
                explicitHumanBillCode = "BILL-0001",
                customerNoteTh = "บิลทดสอบ CART01",
                actorId = "LOCAL_ADMIN",
                lines = cart01Lines,
                nowEpochMs = currentClockMs + 1_000L
            )
        )
        assertTrue(commit1 is AtomicCommitResult.Committed)

        // สร้างบิลที่ 2 แล้วสั่งยกเลิก (CANCELLED) เพื่อพิสูจน์ว่าบิลที่ยกเลิกไม่ถูกนำมาคำนวณสิทธิได้รับรางวัล
        val cancelledLines = listOf(
            PersistedLineCommitInput("31", BetSide.TOP.name, 10_000L, "60", 600_000L)
        )
        backend.insertQuoteSnapshot(
            QuoteSnapshotEntity(
                quoteId = "q-bill2",
                roundId = roundId,
                policyVersion = 1L,
                payloadSha256 = "hash-bill2",
                totalAmountMinor = 10_000L,
                totalProvisionalPayoutMinor = 600_000L,
                lineCount = 1,
                distinctNumberCount = 1,
                createdAtEpochMs = currentClockMs,
                expiresAtEpochMs = currentClockMs + 300_000L
            )
        )
        orderRepository.commitNewBillAtomically(
            AtomicBillCommitRequest(
                operationKey = "op-commit-bill2",
                payloadSha256 = "hash-bill2",
                quoteId = "q-bill2",
                cartIdToClear = null,
                roundId = roundId,
                expectedPolicyVersion = 1L,
                newBillId = "bill-cancelled-02",
                newRevisionId = "rev-bill2-1",
                explicitHumanBillCode = "BILL-0002",
                customerNoteTh = "บิลที่ถูกยกเลิก",
                actorId = "LOCAL_ADMIN",
                lines = cancelledLines,
                nowEpochMs = currentClockMs + 2_000L
            )
        )
        orderRepository.cancelBillAtomically(
            operationKey = "op-cancel-bill2",
            payloadSha256 = "hash-cancel-bill2",
            billId = "bill-cancelled-02",
            cancelReasonTh = "ลูกค้าขอยกเลิกก่อนปิดรับ",
            actorId = "LOCAL_ADMIN",
            nowEpochMs = currentClockMs + 3_000L
        )
    }

    @Test
    fun previewAndApproveResult_matchesCart01Fixture_enforcesIdempotency_andSeparatesEntitlementFromPaidCash() = runTest {
        val controller = ReportsAndResultWorkflowController(
            bootstrapRepository = bootstrapRepository,
            orderRepository = orderRepository,
            scope = backgroundScope,
            clockEpochMsProvider = { currentClockMs += 1_000L; currentClockMs }
        )
        controller.refreshRoundReportSnapshot(roundId)

        // 1. กรอกเลขรางวัล บน=31, ล่าง=27 แต่ยังไม่กด Preview -> การกด Approve ต้องถูกปฏิเสธ
        controller.updateWinningTopInput("31")
        controller.updateWinningBottomInput("27")
        controller.updateApprovalReasonInput("ประกาศผลรางวัลประจำงวดครั้งแรก")

        val prematureApprove = controller.approvePreviewedResultRevision()
        assertTrue(prematureApprove is ResultApprovalOutcome.Rejected)
        assertEquals(
            "PREVIEW_REQUIRED_BEFORE_APPROVAL",
            (prematureApprove as ResultApprovalOutcome.Rejected).code
        )

        // 2. กดคำนวณตัวอย่างผลรางวัล (Preview) -> ต้องตรงกับ 07_TEST_FIXTURES.json (top31_bottom27_payout_minor = 1800000)
        val previewRes = controller.previewResultEntitlement()
        assertTrue(previewRes is ResultEntitlementPreviewOutcome.ValidPreview)
        val summary = (previewRes as ResultEntitlementPreviewOutcome.ValidPreview).summary
        assertEquals(1, summary.activeBillCount)
        assertEquals(1, summary.cancelledBillCount)
        assertEquals(35_000L, summary.totalActiveReceiveMinor)
        assertEquals(1, summary.winningBillCount)
        assertEquals(2, summary.winningLineCount)
        assertEquals(600_000L, summary.topEntitlementPayoutMinor)
        assertEquals(1_200_000L, summary.bottomEntitlementPayoutMinor)
        assertEquals(1_800_000L, summary.totalEntitlementPayoutMinor)

        // 3. ยืนยันอนุมัติผลรางวัล (Approve Result Revision #1)
        val opKey1 = controller.uiState.value.pendingApprovalOperationKey!!
        val approve1 = controller.approvePreviewedResultRevision(explicitOperationKey = opKey1)
        assertTrue(approve1 is ResultApprovalOutcome.Approved)
        val approved1 = approve1 as ResultApprovalOutcome.Approved
        assertEquals(1, approved1.revisionNumber)
        assertEquals(2, approved1.winningEntitlementCount)
        assertEquals(1_800_000L, approved1.totalEntitlementPayoutMinor)
        assertFalse(approved1.replayedFromDurableOperation)
        assertFalse(approved1.replayedFromIdenticalActiveResult)

        // 4. ยืนยันว่า Winning Entitlement != Paid Cash (ยอดจ่ายจริงยังเป็น 0 และยอดคงค้างเท่ากับ 1,800,000 สตางค์)
        val reportAfterApprove1 = controller.uiState.value.reportSnapshot!!
        assertEquals(1_800_000L, reportAfterApprove1.totalEntitlementPayoutMinor)
        assertEquals(0L, reportAfterApprove1.totalAllocatedPaidCashMinor)
        assertEquals(1_800_000L, reportAfterApprove1.unpaidEntitlementBalanceMinor)

        // 5. ทดสอบ Idempotency กรณีส่ง operation_key เดิมซ้ำ -> ต้อง Replay โดยไม่สร้างแถวซ้ำ
        val replaySameKey = controller.approvePreviewedResultRevision(explicitOperationKey = opKey1)
        assertTrue(replaySameKey is ResultApprovalOutcome.Approved)
        assertTrue((replaySameKey as ResultApprovalOutcome.Approved).replayedFromDurableOperation)
        assertEquals(1, backend.resultRevisions.size)
        assertEquals(2, backend.winningEntitlements.size)

        // 6. ทดสอบ Idempotency กรณีใช้ operation_key ใหม่แต่เลขรางวัล บน=31, ล่าง=27 ตรงกับรุ่น APPROVED ปัจจุบัน
        val replayIdenticalResult = controller.approvePreviewedResultRevision(
            explicitOperationKey = "op-result-second-click-new-key"
        )
        assertTrue(replayIdenticalResult is ResultApprovalOutcome.Approved)
        assertTrue((replayIdenticalResult as ResultApprovalOutcome.Approved).replayedFromIdenticalActiveResult)
        assertEquals(1, backend.resultRevisions.size)
        assertEquals(2, backend.winningEntitlements.size)

        // 7. ทดสอบ PayloadConflict เมื่อใช้ operation_key เดิมแต่ส่ง payload_sha256 ต่างออกไป
        val conflictOutcome = orderRepository.approveResultRevisionAtomically(
            ApproveResultRevisionRequest(
                operationKey = opKey1,
                payloadSha256 = PasteBatchParserAndQuoteHasher.sha256Hex("different_payload"),
                roundId = roundId,
                newResultRevisionId = "resrev-conflict",
                approvalId = "apv-conflict",
                winningTopTwoDigit = "99",
                winningBottomTwoDigit = "88",
                reasonTh = "ขัดแย้ง",
                actorId = "LOCAL_ADMIN",
                nowEpochMs = currentClockMs + 5_000L
            )
        )
        assertTrue(conflictOutcome is ResultApprovalOutcome.PayloadConflict)
    }

    @Test
    fun resultRevisionCorrection_supersedesOldRevisionPreservingHistory_andSupportsBillAndNumberSideDrilldowns() = runTest {
        val controller = ReportsAndResultWorkflowController(
            bootstrapRepository = bootstrapRepository,
            orderRepository = orderRepository,
            scope = backgroundScope,
            clockEpochMsProvider = { currentClockMs += 1_000L; currentClockMs }
        )
        controller.refreshRoundReportSnapshot(roundId)

        // ประกาศผลรุ่นที่ #1: บน=31, ล่าง=27 (สิทธิรวม = 1,800,000 สตางค์)
        controller.updateWinningTopInput("31")
        controller.updateWinningBottomInput("27")
        controller.updateApprovalReasonInput("ประกาศผลรุ่นแรก")
        controller.previewResultEntitlement()
        val rev1Outcome = controller.approvePreviewedResultRevision() as ResultApprovalOutcome.Approved
        assertEquals(1, rev1Outcome.revisionNumber)
        assertEquals(1_800_000L, rev1Outcome.totalEntitlementPayoutMinor)

        // แก้ไขผลรางวัลเป็นรุ่นที่ #2: บน=31, ล่าง=31 (31 บน = 600,000 + 31 ล่าง = 300,000 -> รวม 900,000 สตางค์)
        controller.updateWinningTopInput("31")
        controller.updateWinningBottomInput("31")
        // เมื่อเปลี่ยนเลขล่าง previewSummary ต้องถูกล้างเพื่อบังคับให้กด Preview ใหม่ก่อน
        assertEquals(null, controller.uiState.value.previewSummary)

        controller.updateApprovalReasonInput("แก้ไขผลรางวัลเลขท้าย 2 ตัวล่างตามใบตรวจทาน")
        val preview2 = controller.previewResultEntitlement() as ResultEntitlementPreviewOutcome.ValidPreview
        assertEquals(900_000L, preview2.summary.totalEntitlementPayoutMinor)

        val rev2Outcome = controller.approvePreviewedResultRevision() as ResultApprovalOutcome.Approved
        assertEquals(2, rev2Outcome.revisionNumber)
        assertEquals(900_000L, rev2Outcome.totalEntitlementPayoutMinor)

        // ตรวจสอบประวัติ Result Revisions: รุ่นที่ #1 ต้องเป็น SUPERSEDED และรุ่นที่ #2 ต้องเป็น APPROVED
        val allRevisions = orderRepository.getResultRevisionsForRound(roundId)
        assertEquals(2, allRevisions.size)
        val latestRev = allRevisions.first { it.revisionNumber == 2 }
        val oldRev = allRevisions.first { it.revisionNumber == 1 }
        assertEquals(ResultRevisionStatus.APPROVED.name, latestRev.status)
        assertEquals(ResultRevisionStatus.SUPERSEDED.name, oldRev.status)

        // ทดสอบเจาะลึกรายบิล (Bill & Line Entitlement Drilldown)
        controller.selectBillForDrilldown("bill-cart01")
        val billDrilldown = controller.uiState.value.selectedBillDrilldown
        assertNotNull(billDrilldown)
        assertEquals("BILL-0001", billDrilldown?.bill?.humanBillCode)
        assertEquals(900_000L, billDrilldown?.totalEntitlementPayoutMinor)
        assertEquals(0L, billDrilldown?.totalAllocatedPaidCashMinor)
        assertEquals(900_000L, billDrilldown?.unpaidEntitlementBalanceMinor)
        assertEquals(3, billDrilldown?.lines?.size)
        assertEquals(2, billDrilldown?.lines?.count { it.isWinningLine })

        // ทดสอบเจาะลึกรายเลข 00–99 (Number-Side Drilldown สำหรับเลข 31 บน)
        controller.selectNumberSideForDrilldown("31", BetSide.TOP)
        val numSideDrilldown = controller.uiState.value.selectedNumberSideDrilldown
        assertNotNull(numSideDrilldown)
        assertEquals("31", numSideDrilldown?.numberTwoDigit)
        assertEquals(BetSide.TOP, numSideDrilldown?.side)
        assertEquals(10_000L, numSideDrilldown?.totalPrincipalMinor)
        assertEquals(600_000L, numSideDrilldown?.totalEntitlementPayoutMinor)
        assertEquals(1, numSideDrilldown?.contributingLines?.size)
    }
}
