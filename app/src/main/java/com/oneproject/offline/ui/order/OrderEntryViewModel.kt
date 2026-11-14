package com.oneproject.offline.ui.order

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.oneproject.offline.core.money.ExactMoneyMath
import com.oneproject.offline.data.local.dao.AtomicBillCommitRequest
import com.oneproject.offline.data.local.dao.AtomicCommitResult
import com.oneproject.offline.data.local.dao.CartLineDraft
import com.oneproject.offline.data.local.dao.PersistedLineCommitInput
import com.oneproject.offline.data.local.entity.BetSide
import com.oneproject.offline.data.local.entity.CartLineEntity
import com.oneproject.offline.data.local.entity.DurableOperationStatus
import com.oneproject.offline.data.local.entity.QuoteSnapshotEntity
import com.oneproject.offline.data.repository.AuthoritativeOrderRepository
import com.oneproject.offline.data.repository.OfflineBootstrapRepository
import com.oneproject.offline.domain.engine.DomainOrderLineInput
import com.oneproject.offline.domain.engine.DomainPolicyDefaults
import com.oneproject.offline.domain.engine.NumberExpansionEngine
import com.oneproject.offline.domain.engine.PasteBatchParseResult
import com.oneproject.offline.domain.engine.PasteBatchParserAndQuoteHasher
import com.oneproject.offline.domain.engine.PasteLineError
import com.oneproject.offline.domain.engine.PolicyAndCapEngine
import com.oneproject.offline.domain.engine.QuoteEvaluationOutcome
import com.oneproject.offline.domain.engine.QuotedPersistedLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class OrderSideSelection(val thaiLabel: String, val sides: List<BetSide>) {
    TOP_ONLY("บนอย่างเดียว", listOf(BetSide.TOP)),
    BOTTOM_ONLY("ล่างอย่างเดียว", listOf(BetSide.BOTTOM)),
    TOP_AND_BOTTOM("บน + ล่าง", listOf(BetSide.TOP, BetSide.BOTTOM))
}

data class ReviewQuoteUiModel(
    val quoteId: String,
    val operationKey: String,
    val roundId: String,
    val roundCode: String,
    val boundPolicyVersion: Long,
    val payloadSha256: String,
    val quotedLines: List<QuotedPersistedLine>,
    val totalAmountMinor: Long,
    val totalProvisionalPayoutMinor: Long,
    val lineCount: Int,
    val distinctNumberCount: Int,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long
)

data class CommittedBillReceiptUiModel(
    val committedBillId: String,
    val committedRevisionId: String,
    val humanBillCode: String,
    val revisionNumber: Int,
    val operationKey: String,
    val totalAmountMinor: Long,
    val lineCount: Int,
    val distinctNumberCount: Int,
    val replayedFromDurableOperation: Boolean
)

data class OrderEntryUiState(
    val activeRoundId: String = "round-demo-initial-001",
    val activeRoundCode: String = "DEMO-2026-001",
    val activePolicyVersion: Long = 1L,
    val defaultTopRateExact: String = "60",
    val defaultBottomRateExact: String = "60",
    val selectedTabIndex: Int = 0,

    // แท็บ 1: กรอกเอง (Manual)
    val manualNumberInput: String = "",
    val manualSideSelection: OrderSideSelection = OrderSideSelection.TOP_ONLY,
    val manualIncludeReverse: Boolean = false,
    val manualAmountThbInput: String = "100",

    // แท็บ 2: เลือกหลายเลข (Grid 00–99 10×10)
    val gridSelectedNumbers: Set<String> = emptySet(),
    val gridUndoStack: List<Set<String>> = emptyList(),
    val gridSelectedDigit: String = "1",
    val gridSideSelection: OrderSideSelection = OrderSideSelection.TOP_ONLY,
    val gridAmountPerNumberThbInput: String = "100",
    val isGridExpandedForNarrowScreen: Boolean = false,

    // แท็บ 3: วางข้อความ (Paste Text)
    val pasteRawTextInput: String = "",
    val pasteErrors: List<PasteLineError> = emptyList(),

    // ตะกร้าในเครื่อง (Durable Cart — ไม่ใช่การจองวงเงิน)
    val cartId: String = "cart-round-demo-initial-001",
    val customerNoteTh: String = "",
    val cartLines: List<CartLineEntity> = emptyList(),
    val cartDistinctNumberCount: Int = 0,
    val cartLineCount: Int = 0,
    val cartTotalAmountMinor: Long = 0L,
    val showClearCartConfirmDialog: Boolean = false,

    // หน้าตรวจทาน (Review) และหน้าสำเร็จ (Committed Receipt)
    val activeReviewQuote: ReviewQuoteUiModel? = null,
    val committedReceipt: CommittedBillReceiptUiModel? = null,
    val lastPendingOperationKey: String? = null,
    val feedbackBannerTh: String? = null,
    val errorBannerTh: String? = null
) {
    /**
     * กฎ 05_UI & D04:
     * หากนโยบายเปลี่ยนระหว่างที่เปิดหน้าตรวจทาน (Review) ต้องแสดงคำเตือนและบังคับคำนวณใบตรวจทานใหม่ก่อนยืนยัน
     */
    val hasPolicyChangedDuringReview: Boolean
        get() = activeReviewQuote != null && activeReviewQuote.boundPolicyVersion != activePolicyVersion
}

/**
 * ตัวควบคุมหลักสำหรับหน้าจอรับรายการ ตะกร้า หน้าตรวจทาน และการทำ Atomic Commit ลง Room (Phase 04)
 */
class OrderEntryWorkflowController(
    private val orderRepository: AuthoritativeOrderRepository,
    private val clockEpochMsProvider: () -> Long = { System.currentTimeMillis() },
    private val uuidProvider: () -> String = { UUID.randomUUID().toString() }
) {
    private val _state = MutableStateFlow(OrderEntryUiState())
    val state: StateFlow<OrderEntryUiState> = _state.asStateFlow()

    fun syncRoundAndPolicyHeader(
        roundId: String,
        roundCode: String,
        policyVersion: Long,
        topRateExact: String,
        bottomRateExact: String
    ) {
        val cartId = "cart-$roundId"
        _state.update { current ->
            current.copy(
                activeRoundId = roundId,
                activeRoundCode = roundCode,
                activePolicyVersion = policyVersion,
                defaultTopRateExact = topRateExact,
                defaultBottomRateExact = bottomRateExact,
                cartId = cartId
            )
        }
    }

    suspend fun refreshCartFromRoom() {
        val currentCartId = _state.value.cartId
        val lines = orderRepository.getCartLinesSnapshot(currentCartId)
        applyCartLinesSnapshot(lines)
    }

    fun applyCartLinesSnapshot(lines: List<CartLineEntity>) {
        val distinctCount = lines.map { it.numberTwoDigit }.distinct().size
        val totalMinor = ExactMoneyMath.checkedSum(lines.map { it.amountMinor })
        _state.update { current ->
            current.copy(
                cartLines = lines,
                cartDistinctNumberCount = distinctCount,
                cartLineCount = lines.size,
                cartTotalAmountMinor = totalMinor
            )
        }
    }

    fun selectTab(tabIndex: Int) {
        _state.update { it.copy(selectedTabIndex = tabIndex.coerceIn(0, 2), errorBannerTh = null) }
    }

    fun updateCustomerNote(note: String) {
        _state.update { it.copy(customerNoteTh = note) }
    }

    // =========================================================================
    // แท็บ 1: กรอกเอง (Manual Entry)
    // =========================================================================

    fun updateManualInputs(
        numberInput: String = _state.value.manualNumberInput,
        sideSelection: OrderSideSelection = _state.value.manualSideSelection,
        includeReverse: Boolean = _state.value.manualIncludeReverse,
        amountThbInput: String = _state.value.manualAmountThbInput
    ) {
        _state.update {
            it.copy(
                manualNumberInput = numberInput,
                manualSideSelection = sideSelection,
                manualIncludeReverse = includeReverse,
                manualAmountThbInput = amountThbInput,
                errorBannerTh = null
            )
        }
    }

    suspend fun submitManualEntryToCart(): Boolean {
        val snap = _state.value
        val validNum = try {
            NumberExpansionEngine.validateTwoDigitNumber(snap.manualNumberInput)
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(errorBannerTh = e.message) }
            return false
        }

        val amountMinor = try {
            ExactMoneyMath.parseThbToMinorUnits(snap.manualAmountThbInput)
        } catch (e: RuntimeException) {
            _state.update { it.copy(errorBannerTh = e.message) }
            return false
        }

        val numbers = if (snap.manualIncludeReverse) {
            NumberExpansionEngine.expandReverseUnion(listOf(validNum))
        } else {
            listOf(validNum)
        }

        val drafts = mutableListOf<CartLineDraft>()
        for (num in numbers) {
            for (side in snap.manualSideSelection.sides) {
                drafts.add(
                    CartLineDraft(
                        numberTwoDigit = num,
                        side = side.name,
                        amountMinor = amountMinor,
                        entrySource = "MANUAL"
                    )
                )
            }
        }

        orderRepository.appendLinesToCart(
            cartId = snap.cartId,
            roundId = snap.activeRoundId,
            customerNoteTh = snap.customerNoteTh,
            newLines = drafts,
            nowEpochMs = clockEpochMsProvider()
        )
        refreshCartFromRoom()
        _state.update {
            it.copy(
                manualNumberInput = "",
                activeReviewQuote = null,
                feedbackBannerTh = "เพิ่ม ${drafts.size} รายการลงตะกร้าแล้ว (ตะกร้ายังไม่ใช่การจองวงเงิน)",
                errorBannerTh = null
            )
        }
        return true
    }

    // =========================================================================
    // แท็บ 2: เลือกหลายเลข (Grid 00–99 10×10 + Undo + รูดหน้า/หลัง/19ประตู/กลับ + Expanded View)
    // =========================================================================

    private fun pushGridSelection(newSelection: Set<String>) {
        _state.update { current ->
            if (newSelection == current.gridSelectedNumbers) {
                current
            } else {
                current.copy(
                    gridUndoStack = current.gridUndoStack + listOf(current.gridSelectedNumbers),
                    gridSelectedNumbers = newSelection.toSortedSet(),
                    errorBannerTh = null
                )
            }
        }
    }

    fun toggleGridNumber(numberTwoDigit: String) {
        val valid = NumberExpansionEngine.validateTwoDigitNumber(numberTwoDigit)
        val currentSet = _state.value.gridSelectedNumbers
        val nextSet = if (currentSet.contains(valid)) {
            currentSet - valid
        } else {
            currentSet + valid
        }
        pushGridSelection(nextSet)
    }

    fun gridSelectAll100() {
        pushGridSelection(NumberExpansionEngine.ALL_TWO_DIGIT_NUMBERS.toSet())
    }

    fun gridClearSelection() {
        pushGridSelection(emptySet())
    }

    fun gridUndoLastSelectionChange(): Boolean {
        val stack = _state.value.gridUndoStack
        if (stack.isEmpty()) return false
        val previous = stack.last()
        _state.update { current ->
            current.copy(
                gridSelectedNumbers = previous,
                gridUndoStack = stack.dropLast(1),
                errorBannerTh = null
            )
        }
        return true
    }

    fun gridSetHelperDigit(digit: String) {
        val validDigit = NumberExpansionEngine.validateSingleDigit(digit).toString()
        _state.update { it.copy(gridSelectedDigit = validDigit, errorBannerTh = null) }
    }

    fun gridApplyFrontDigit(digit: String = _state.value.gridSelectedDigit) {
        val expanded = NumberExpansionEngine.expandFront(digit)
        pushGridSelection(_state.value.gridSelectedNumbers + expanded)
    }

    fun gridApplyBackDigit(digit: String = _state.value.gridSelectedDigit) {
        val expanded = NumberExpansionEngine.expandBack(digit)
        pushGridSelection(_state.value.gridSelectedNumbers + expanded)
    }

    fun gridApplyDoor19Digit(digit: String = _state.value.gridSelectedDigit) {
        val expanded = NumberExpansionEngine.expandDoor19(digit)
        pushGridSelection(_state.value.gridSelectedNumbers + expanded)
    }

    fun gridApplyReverseUnionOnSelected() {
        val currentSelected = _state.value.gridSelectedNumbers.toList()
        if (currentSelected.isEmpty()) {
            _state.update { it.copy(errorBannerTh = "กรุณาเลือกเลขอย่างน้อย 1 เลขก่อนกดกลับเลข") }
            return
        }
        val union = NumberExpansionEngine.expandReverseUnion(currentSelected)
        pushGridSelection(union.toSet())
    }

    fun setGridExpandedForNarrowScreen(expanded: Boolean) {
        _state.update { it.copy(isGridExpandedForNarrowScreen = expanded) }
    }

    fun updateGridOrderOptions(
        sideSelection: OrderSideSelection = _state.value.gridSideSelection,
        amountPerNumberThbInput: String = _state.value.gridAmountPerNumberThbInput
    ) {
        _state.update {
            it.copy(
                gridSideSelection = sideSelection,
                gridAmountPerNumberThbInput = amountPerNumberThbInput,
                errorBannerTh = null
            )
        }
    }

    suspend fun submitGridSelectionToCart(): Boolean {
        val snap = _state.value
        val sortedNumbers = snap.gridSelectedNumbers.toSortedSet().toList()
        if (sortedNumbers.isEmpty()) {
            _state.update { it.copy(errorBannerTh = "กรุณาเลือกเลขในตาราง 00–99 อย่างน้อย 1 เลข") }
            return false
        }

        val amountMinor = try {
            ExactMoneyMath.parseThbToMinorUnits(snap.gridAmountPerNumberThbInput)
        } catch (e: RuntimeException) {
            _state.update { it.copy(errorBannerTh = e.message) }
            return false
        }

        val drafts = mutableListOf<CartLineDraft>()
        for (num in sortedNumbers) {
            for (side in snap.gridSideSelection.sides) {
                drafts.add(
                    CartLineDraft(
                        numberTwoDigit = num,
                        side = side.name,
                        amountMinor = amountMinor,
                        entrySource = "GRID"
                    )
                )
            }
        }

        orderRepository.appendLinesToCart(
            cartId = snap.cartId,
            roundId = snap.activeRoundId,
            customerNoteTh = snap.customerNoteTh,
            newLines = drafts,
            nowEpochMs = clockEpochMsProvider()
        )
        refreshCartFromRoom()
        _state.update {
            it.copy(
                gridSelectedNumbers = emptySet(),
                gridUndoStack = emptyList(),
                isGridExpandedForNarrowScreen = false,
                activeReviewQuote = null,
                feedbackBannerTh = "เพิ่ม ${ sortedNumbers.size } เลข (${ drafts.size } รายการ) ลงตะกร้าแล้ว",
                errorBannerTh = null
            )
        }
        return true
    }

    // =========================================================================
    // แท็บ 3: วางข้อความ (Paste Batch — ผิดแม้จุดเดียวบล็อกทั้งชุด D03)
    // =========================================================================

    fun updatePasteText(rawText: String) {
        _state.update { it.copy(pasteRawTextInput = rawText, pasteErrors = emptyList(), errorBannerTh = null) }
    }

    suspend fun submitPasteBatchToCart(): Boolean {
        val snap = _state.value
        return when (val parsed = PasteBatchParserAndQuoteHasher.parsePasteBatchStrict(snap.pasteRawTextInput)) {
            is PasteBatchParseResult.BlockedBatch -> {
                _state.update {
                    it.copy(
                        pasteErrors = parsed.errors,
                        errorBannerTh = "บล็อกทั้งชุด (${parsed.errors.size} จุดผิดพลาด): กรุณาแก้ไขบรรทัดที่แจ้งเตือนก่อนนำเข้าตะกร้า"
                    )
                }
                false
            }
            is PasteBatchParseResult.AcceptedBatch -> {
                val drafts = parsed.lines.map { line ->
                    CartLineDraft(
                        numberTwoDigit = line.numberTwoDigit,
                        side = line.side.name,
                        amountMinor = line.amountMinor,
                        entrySource = "PASTE"
                    )
                }
                orderRepository.appendLinesToCart(
                    cartId = snap.cartId,
                    roundId = snap.activeRoundId,
                    customerNoteTh = snap.customerNoteTh,
                    newLines = drafts,
                    nowEpochMs = clockEpochMsProvider()
                )
                refreshCartFromRoom()
                _state.update {
                    it.copy(
                        pasteRawTextInput = "",
                        pasteErrors = emptyList(),
                        activeReviewQuote = null,
                        feedbackBannerTh = "นำเข้า ${drafts.size} รายการจากข้อความลงตะกร้าสำเร็จ",
                        errorBannerTh = null
                    )
                }
                true
            }
        }
    }

    // =========================================================================
    // จัดการตะกร้า (Cart แก้ไข / ลบ / ล้างพร้อมยืนยัน)
    // =========================================================================

    suspend fun editCartLineAmountThb(cartLineId: Long, newAmountThbText: String): Boolean {
        val snap = _state.value
        val newAmountMinor = try {
            ExactMoneyMath.parseThbToMinorUnits(newAmountThbText)
        } catch (e: RuntimeException) {
            _state.update { it.copy(errorBannerTh = e.message) }
            return false
        }
        orderRepository.updateCartLineAmount(
            cartId = snap.cartId,
            cartLineId = cartLineId,
            newAmountMinor = newAmountMinor
        )
        refreshCartFromRoom()
        _state.update {
            it.copy(
                activeReviewQuote = null,
                feedbackBannerTh = "แก้ไขยอดรายการในตะกร้าเรียบร้อยแล้ว",
                errorBannerTh = null
            )
        }
        return true
    }

    suspend fun removeCartLine(cartLineId: Long) {
        val snap = _state.value
        orderRepository.deleteCartLine(cartId = snap.cartId, cartLineId = cartLineId)
        refreshCartFromRoom()
        _state.update {
            it.copy(
                activeReviewQuote = null,
                feedbackBannerTh = "ลบรายการออกจากตะกร้าแล้ว",
                errorBannerTh = null
            )
        }
    }

    fun requestClearCartConfirmation(show: Boolean) {
        _state.update { it.copy(showClearCartConfirmDialog = show) }
    }

    suspend fun confirmClearAllCartLines() {
        val snap = _state.value
        orderRepository.clearCart(snap.cartId)
        refreshCartFromRoom()
        _state.update {
            it.copy(
                showClearCartConfirmDialog = false,
                activeReviewQuote = null,
                feedbackBannerTh = "ล้างรายการทั้งหมดในตะกร้าเรียบร้อยแล้ว",
                errorBannerTh = null
            )
        }
    }

    // =========================================================================
    // ตรวจทาน (Review) และยืนยันบันทึกบิลแบบ Atomic Commit (Local Confirm)
    // =========================================================================

    suspend fun prepareReviewQuote(
        explicitOperationKey: String? = null,
        quoteTtlMs: Long = 300_000L
    ): Boolean {
        refreshCartFromRoom()
        val snap = _state.value
        if (snap.cartLines.isEmpty()) {
            _state.update { it.copy(errorBannerTh = "ตะกร้าว่างเปล่า กรุณาเพิ่มรายการเลขก่อนกดตรวจทาน") }
            return false
        }

        val round = orderRepository.getRoundSnapshot(snap.activeRoundId)
        if (round == null) {
            _state.update { it.copy(errorBannerTh = "ไม่พบงวดปัจจุบันในฐานข้อมูลเครื่อง") }
            return false
        }
        val policyEntity = orderRepository.getPolicyVersionSnapshot(round.roundId, round.activePolicyVersion)
        if (policyEntity == null) {
            _state.update { it.copy(errorBannerTh = "ไม่พบรุ่นนโยบาย #${round.activePolicyVersion} ในเครื่อง") }
            return false
        }

        val domainInputs = snap.cartLines.map { line ->
            DomainOrderLineInput(
                numberTwoDigit = line.numberTwoDigit,
                side = BetSide.valueOf(line.side),
                amountMinor = line.amountMinor
            )
        }
        val keys = domainInputs.map { it.numberTwoDigit to it.side }.toSet()
        val overrides = orderRepository.getOverridesForKeys(round.roundId, round.activePolicyVersion, keys)
        val counters = orderRepository.getActiveCountersMapForRound(round.roundId)

        val defaultPolicy = DomainPolicyDefaults(
            policyVersion = policyEntity.policyVersion,
            defaultTopRateExact = policyEntity.defaultTopRateExact,
            defaultBottomRateExact = policyEntity.defaultBottomRateExact,
            defaultReceiveCapMinor = policyEntity.defaultReceiveCapMinor,
            defaultPayoutCapMinor = policyEntity.defaultPayoutCapMinor
        )

        val evaluation = PolicyAndCapEngine.evaluateAndQuoteOrderLines(
            rawLines = domainInputs,
            defaultPolicy = defaultPolicy,
            overridesByKey = overrides,
            currentActiveCountersByKey = counters
        )

        when (evaluation) {
            is QuoteEvaluationOutcome.Rejected -> {
                _state.update {
                    it.copy(
                        activeReviewQuote = null,
                        errorBannerTh = "ไม่ผ่านการตรวจเพดาน/นโยบาย (${evaluation.code}): ${evaluation.messageTh}"
                    )
                }
                return false
            }
            is QuoteEvaluationOutcome.ValidQuote -> {
                val now = clockEpochMsProvider()
                val quoteId = "quote-${uuidProvider()}"
                val payloadSha256 = PasteBatchParserAndQuoteHasher.computeCanonicalQuotePayloadSha256(
                    roundId = round.roundId,
                    policyVersion = round.activePolicyVersion,
                    quotedLines = evaluation.quotedLines
                )
                val operationKey = explicitOperationKey ?: "op-commit-${uuidProvider()}"

                orderRepository.saveQuoteSnapshot(
                    QuoteSnapshotEntity(
                        quoteId = quoteId,
                        cartId = snap.cartId,
                        roundId = round.roundId,
                        policyVersion = round.activePolicyVersion,
                        payloadSha256 = payloadSha256,
                        totalAmountMinor = evaluation.totalAmountMinor,
                        lineCount = evaluation.lineCount,
                        distinctNumberCount = evaluation.distinctNumberCount,
                        createdAtEpochMs = now,
                        expiresAtEpochMs = now + quoteTtlMs
                    )
                )

                val reviewUi = ReviewQuoteUiModel(
                    quoteId = quoteId,
                    operationKey = operationKey,
                    roundId = round.roundId,
                    roundCode = round.roundCode,
                    boundPolicyVersion = round.activePolicyVersion,
                    payloadSha256 = payloadSha256,
                    quotedLines = evaluation.quotedLines,
                    totalAmountMinor = evaluation.totalAmountMinor,
                    totalProvisionalPayoutMinor = evaluation.totalProvisionalPayoutMinor,
                    lineCount = evaluation.lineCount,
                    distinctNumberCount = evaluation.distinctNumberCount,
                    createdAtEpochMs = now,
                    expiresAtEpochMs = now + quoteTtlMs
                )

                _state.update {
                    it.copy(
                        activeRoundId = round.roundId,
                        activeRoundCode = round.roundCode,
                        activePolicyVersion = round.activePolicyVersion,
                        activeReviewQuote = reviewUi,
                        lastPendingOperationKey = operationKey,
                        committedReceipt = null,
                        feedbackBannerTh = "สร้างใบตรวจทานสำเร็จ กรุณาตรวจสอบอัตราจ่ายและยอดรวมก่อนกดยืนยันบันทึกบิล",
                        errorBannerTh = null
                    )
                }
                return true
            }
        }
    }

    fun cancelReviewMode() {
        _state.update { it.copy(activeReviewQuote = null, errorBannerTh = null) }
    }

    /**
     * ยืนยันบันทึกบิลลง Room แบบ Atomic Commit
     * กฎข้อ 01 & 05_UI:
     * - ห้ามแสดงหน้าสำเร็จก่อนที่ Transaction จะ Commit สำเร็จและมี committedBillId ที่แน่นอน
     * - หาก Policy เปลี่ยนระหว่างอยู่หน้า Review ระบบใน Room Transaction จะบล็อกและบังคับให้ตรวจทานใหม่
     */
    suspend fun confirmReviewAndCommitLocalBill(
        actorId: String = "ACTOR-LOCAL-OPERATOR"
    ): AtomicCommitResult {
        val snap = _state.value
        val review = snap.activeReviewQuote
            ?: return AtomicCommitResult.Rejected(
                code = "NO_ACTIVE_REVIEW_QUOTE",
                messageTh = "กรุณากดตรวจทานรายการก่อนยืนยันบันทึกบิล"
            )

        val request = AtomicBillCommitRequest(
            operationKey = review.operationKey,
            payloadSha256 = review.payloadSha256,
            quoteId = review.quoteId,
            cartIdToClear = snap.cartId,
            roundId = review.roundId,
            expectedPolicyVersion = review.boundPolicyVersion,
            newBillId = "bill-${uuidProvider()}",
            newRevisionId = "rev-${uuidProvider()}",
            customerNoteTh = snap.customerNoteTh,
            actorId = actorId,
            lines = review.quotedLines.map { line ->
                PersistedLineCommitInput(
                    numberTwoDigit = line.numberTwoDigit,
                    side = line.side.name,
                    amountMinor = line.amountMinor,
                    rateSnapshotExact = line.rateSnapshotExact,
                    provisionalPayoutMinor = line.provisionalPayoutMinor,
                    roundingMode = line.roundingMode
                )
            },
            nowEpochMs = clockEpochMsProvider()
        )

        val commitResult = orderRepository.commitNewBillAtomically(request)
        when (commitResult) {
            is AtomicCommitResult.Committed -> {
                // ต้องมี committedBillId และ humanBillCode ที่บันทึกจริงแล้วเท่านั้นจึงแสดงหน้าสำเร็จ
                require(commitResult.billId.isNotBlank() && commitResult.humanBillCode.isNotBlank()) {
                    "ผลการ Commit ต้องมี committedBillId และ humanBillCode เสมอ"
                }
                refreshCartFromRoom()
                _state.update {
                    it.copy(
                        activeReviewQuote = null,
                        committedReceipt = CommittedBillReceiptUiModel(
                            committedBillId = commitResult.billId,
                            committedRevisionId = commitResult.revisionId,
                            humanBillCode = commitResult.humanBillCode,
                            revisionNumber = commitResult.revisionNumber,
                            operationKey = review.operationKey,
                            totalAmountMinor = review.totalAmountMinor,
                            lineCount = review.lineCount,
                            distinctNumberCount = review.distinctNumberCount,
                            replayedFromDurableOperation = commitResult.replayedFromDurableOperation
                        ),
                        feedbackBannerTh = "บันทึกบิล ${commitResult.humanBillCode} ลงฐานข้อมูลในเครื่องสำเร็จ",
                        errorBannerTh = null
                    )
                }
            }
            is AtomicCommitResult.PayloadConflict -> {
                _state.update {
                    it.copy(
                        errorBannerTh = "ข้อขัดแย้งของ Operation Key (${commitResult.operationKey}): ${commitResult.messageTh}"
                    )
                }
            }
            is AtomicCommitResult.Rejected -> {
                // หากถูกปฏิเสธเพราะ Policy เปลี่ยน ให้อัปเดต activePolicyVersion ใน UI เพื่อให้ป้ายเตือนแสดงทันที
                val latestRound = orderRepository.getRoundSnapshot(review.roundId)
                _state.update {
                    it.copy(
                        activePolicyVersion = latestRound?.activePolicyVersion ?: it.activePolicyVersion,
                        errorBannerTh = "ไม่สามารถบันทึกบิลได้ (${commitResult.code}): ${commitResult.messageTh}"
                    )
                }
            }
        }
        return commitResult
    }

    /**
     * กฎ 05_UI & 01:
     * "หน้าสำเร็จต้องมีcommittedbillid หากไม่แน่ใจตรวจoperationเดิม"
     * ใช้ตรวจสอบและกู้คืนใบเสร็จจาก DurableOperationEntity เมื่อแอปถูกฆ่าโปรเซส (Process Death)
     * หรือเมื่อผู้ใช้ไม่แน่ใจว่ารายการก่อนหน้าบันทึกสำเร็จหรือไม่
     */
    suspend fun verifyOrRecoverPreviousOperation(operationKeyToVerify: String): Boolean {
        val op = orderRepository.findCompletedOrRejectedOperation(operationKeyToVerify.trim())
        if (op == null) {
            _state.update {
                it.copy(
                    errorBannerTh = "ไม่พบบันทึกการทำงานของ Operation Key '$operationKeyToVerify' ในฐานข้อมูลเครื่อง (รายการยังไม่ได้ถูก Commit)"
                )
            }
            return false
        }

        if (op.status == DurableOperationStatus.COMPLETED.name &&
            !op.committedBillId.isNullOrBlank() &&
            !op.humanBillCode.isNullOrBlank()
        ) {
            val billSnapshot = orderRepository.getBillWithActiveLines(op.committedBillId)
            val totalMinor = billSnapshot?.activeLines?.let { lines ->
                ExactMoneyMath.checkedSum(lines.map { it.amountMinor })
            } ?: 0L
            val distinctCount = billSnapshot?.activeLines?.map { it.numberTwoDigit }?.distinct()?.size ?: 0
            val lineCount = billSnapshot?.activeLines?.size ?: 0

            _state.update {
                it.copy(
                    activeReviewQuote = null,
                    committedReceipt = CommittedBillReceiptUiModel(
                        committedBillId = op.committedBillId,
                        committedRevisionId = op.committedRevisionId ?: "",
                        humanBillCode = op.humanBillCode,
                        revisionNumber = billSnapshot?.bill?.latestRevisionNumber ?: 1,
                        operationKey = op.operationKey,
                        totalAmountMinor = totalMinor,
                        lineCount = lineCount,
                        distinctNumberCount = distinctCount,
                        replayedFromDurableOperation = true
                    ),
                    feedbackBannerTh = "ตรวจสอบพบรายการเดิมสำเร็จแล้ว: บิล ${op.humanBillCode} (กู้คืนจาก Durable Operation Log โดยไม่บันทึกซ้ำ)",
                    errorBannerTh = null
                )
            }
            return true
        } else {
            _state.update {
                it.copy(
                    errorBannerTh = "Operation Key '${op.operationKey}' มีสถานะ ${op.status}: ${op.rejectionMessageTh ?: "ไม่ได้บันทึกบิล"}"
                )
            }
            return false
        }
    }

    fun dismissCommittedReceipt() {
        _state.update { it.copy(committedReceipt = null, feedbackBannerTh = null, errorBannerTh = null) }
    }
}

class OrderEntryViewModel(
    private val bootstrapRepository: OfflineBootstrapRepository,
    orderRepository: AuthoritativeOrderRepository
) : ViewModel() {

    val controller = OrderEntryWorkflowController(orderRepository = orderRepository)
    val uiState: StateFlow<OrderEntryUiState> = controller.state

    init {
        viewModelScope.launch {
            bootstrapRepository.observeLatestRound().collectLatest { round ->
                if (round != null) {
                    val policy = orderRepository.getPolicyVersionSnapshot(
                        roundId = round.roundId,
                        policyVersion = round.activePolicyVersion
                    )
                    controller.syncRoundAndPolicyHeader(
                        roundId = round.roundId,
                        roundCode = round.roundCode,
                        policyVersion = round.activePolicyVersion,
                        topRateExact = policy?.defaultTopRateExact ?: "60",
                        bottomRateExact = policy?.defaultBottomRateExact ?: "60"
                    )
                    controller.refreshCartFromRoom()
                }
            }
        }
    }

    fun selectTab(index: Int) = controller.selectTab(index)
    fun updateCustomerNote(note: String) = controller.updateCustomerNote(note)

    fun updateManualInputs(
        numberInput: String = uiState.value.manualNumberInput,
        sideSelection: OrderSideSelection = uiState.value.manualSideSelection,
        includeReverse: Boolean = uiState.value.manualIncludeReverse,
        amountThbInput: String = uiState.value.manualAmountThbInput
    ) = controller.updateManualInputs(numberInput, sideSelection, includeReverse, amountThbInput)

    fun submitManualEntry() {
        viewModelScope.launch { controller.submitManualEntryToCart() }
    }

    fun toggleGridNumber(num: String) = controller.toggleGridNumber(num)
    fun gridSelectAll100() = controller.gridSelectAll100()
    fun gridClearSelection() = controller.gridClearSelection()
    fun gridUndo() = controller.gridUndoLastSelectionChange()
    fun gridSetDigit(digit: String) = controller.gridSetHelperDigit(digit)
    fun gridApplyFront() = controller.gridApplyFrontDigit()
    fun gridApplyBack() = controller.gridApplyBackDigit()
    fun gridApplyDoor19() = controller.gridApplyDoor19Digit()
    fun gridApplyReverse() = controller.gridApplyReverseUnionOnSelected()
    fun setGridExpanded(expanded: Boolean) = controller.setGridExpandedForNarrowScreen(expanded)

    fun updateGridOptions(
        sideSelection: OrderSideSelection = uiState.value.gridSideSelection,
        amountPerNumberThbInput: String = uiState.value.gridAmountPerNumberThbInput
    ) = controller.updateGridOrderOptions(sideSelection, amountPerNumberThbInput)

    fun submitGridSelection() {
        viewModelScope.launch { controller.submitGridSelectionToCart() }
    }

    fun updatePasteText(rawText: String) = controller.updatePasteText(rawText)
    fun submitPasteBatch() {
        viewModelScope.launch { controller.submitPasteBatchToCart() }
    }

    fun editCartLineAmount(cartLineId: Long, newAmountThb: String) {
        viewModelScope.launch { controller.editCartLineAmountThb(cartLineId, newAmountThb) }
    }

    fun removeCartLine(cartLineId: Long) {
        viewModelScope.launch { controller.removeCartLine(cartLineId) }
    }

    fun requestClearCartConfirm(show: Boolean) = controller.requestClearCartConfirmation(show)

    fun confirmClearCart() {
        viewModelScope.launch { controller.confirmClearAllCartLines() }
    }

    fun prepareReviewQuote() {
        viewModelScope.launch { controller.prepareReviewQuote() }
    }

    fun cancelReviewMode() = controller.cancelReviewMode()

    fun confirmReviewAndCommit() {
        viewModelScope.launch { controller.confirmReviewAndCommitLocalBill() }
    }

    fun verifyPreviousOperation(operationKey: String) {
        viewModelScope.launch { controller.verifyOrRecoverPreviousOperation(operationKey) }
    }

    fun dismissReceipt() = controller.dismissCommittedReceipt()

    companion object {
        fun factory(
            bootstrapRepository: OfflineBootstrapRepository,
            orderRepository: AuthoritativeOrderRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return OrderEntryViewModel(bootstrapRepository, orderRepository) as T
            }
        }
    }
}
