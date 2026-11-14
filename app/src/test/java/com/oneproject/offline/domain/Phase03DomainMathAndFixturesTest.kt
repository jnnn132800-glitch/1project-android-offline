package com.oneproject.offline.domain

import com.oneproject.offline.core.money.DomainArithmeticOverflowException
import com.oneproject.offline.core.money.ExactMoneyMath
import com.oneproject.offline.core.money.InvalidMoneyAmountException
import com.oneproject.offline.data.local.entity.BetSide
import com.oneproject.offline.domain.engine.DomainActiveCounterSnapshot
import com.oneproject.offline.domain.engine.DomainNumberPolicyOverride
import com.oneproject.offline.domain.engine.DomainOrderLineInput
import com.oneproject.offline.domain.engine.DomainPolicyDefaults
import com.oneproject.offline.domain.engine.NumberExpansionEngine
import com.oneproject.offline.domain.engine.PasteBatchParseResult
import com.oneproject.offline.domain.engine.PasteBatchParserAndQuoteHasher
import com.oneproject.offline.domain.engine.PolicyAndCapEngine
import com.oneproject.offline.domain.engine.QuoteEvaluationOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ชุดทดสอบสำหรับ Phase 03:
 * ตรวจสอบความถูกต้องของ Domain เลข / เงิน / อัตราจ่าย / เพดาน / Exact Math / Overflow / Paste Block-Batch
 * และตรวจสอบความตรงกันทุกค่ากับ 07_TEST_FIXTURES_abd6d744.json ก่อนเชื่อม UI
 */
class Phase03DomainMathAndFixturesTest {

    @Test
    fun fixtureCart01_matchesExactTotalMinor_andOutcomeTop31Bottom27PayoutMinor() {
        // ข้อมูลตาม 07_TEST_FIXTURES.json ("CART01"):
        // 1) number="31", side="TOP", minor="10000", rate="60"
        // 2) number="31", side="BOTTOM", minor="5000", rate="60"
        // 3) number="27", side="BOTTOM", minor="20000", rate="60"
        // expected total_minor = "35000"
        // expected top31_bottom27_payout_minor = "1800000"
        val defaultPolicy = DomainPolicyDefaults(
            policyVersion = 1L,
            defaultTopRateExact = "60",
            defaultBottomRateExact = "60",
            defaultReceiveCapMinor = 100_000L,
            defaultPayoutCapMinor = 6_000_000L
        )

        val lines = listOf(
            DomainOrderLineInput(
                numberTwoDigit = "31",
                side = BetSide.TOP,
                amountMinor = ExactMoneyMath.parsePositiveMinorUnits("10000")
            ),
            DomainOrderLineInput(
                numberTwoDigit = "31",
                side = BetSide.BOTTOM,
                amountMinor = ExactMoneyMath.parsePositiveMinorUnits("5000")
            ),
            DomainOrderLineInput(
                numberTwoDigit = "27",
                side = BetSide.BOTTOM,
                amountMinor = ExactMoneyMath.parsePositiveMinorUnits("20000")
            )
        )

        val outcome = PolicyAndCapEngine.evaluateAndQuoteOrderLines(
            rawLines = lines,
            defaultPolicy = defaultPolicy,
            overridesByKey = emptyMap(),
            currentActiveCountersByKey = emptyMap()
        )

        assertTrue(outcome is QuoteEvaluationOutcome.ValidQuote)
        val quote = outcome as QuoteEvaluationOutcome.ValidQuote

        assertEquals(35_000L, quote.totalAmountMinor)
        assertEquals(3, quote.lineCount)
        assertEquals(2, quote.distinctNumberCount) // "31" และ "27"

        val payoutTop31Bottom27 = PolicyAndCapEngine.computeGrossPayoutForOutcomeMinor(
            persistedActiveLines = quote.quotedLines,
            winningTopTwoDigit = "31",
            winningBottomTwoDigit = "27"
        )
        assertEquals(1_800_000L, payoutTop31Bottom27)

        // ตรวจสอบว่า Quote Payload SHA-256 เปลี่ยนทันทีเมื่อรุ่นนโยบายหรือยอดเงินเปลี่ยน
        val hashV1 = PasteBatchParserAndQuoteHasher.computeCanonicalQuotePayloadSha256(
            roundId = "round-demo-initial-001",
            policyVersion = 1L,
            quotedLines = quote.quotedLines
        )
        val hashV2 = PasteBatchParserAndQuoteHasher.computeCanonicalQuotePayloadSha256(
            roundId = "round-demo-initial-001",
            policyVersion = 2L,
            quotedLines = quote.quotedLines
        )
        assertEquals(64, hashV1.length)
        assertNotEquals(hashV1, hashV2)
    }

    @Test
    fun fixtureSelection_digit1_matchesFront10Back10Door19_andReverseUnion() {
        // ข้อมูลตาม 07_TEST_FIXTURES.json ("selection"):
        // digit="1", front_count=10, back_count=10, door19_count=19
        // reverse_input=["31","11"] -> reverse_union=["11","13","31"]
        val front = NumberExpansionEngine.expandFront("1")
        val back = NumberExpansionEngine.expandBack("1")
        val door19 = NumberExpansionEngine.expandDoor19("1")

        assertEquals(10, front.size)
        assertEquals(listOf("10", "11", "12", "13", "14", "15", "16", "17", "18", "19"), front)

        assertEquals(10, back.size)
        assertEquals(listOf("01", "11", "21", "31", "41", "51", "61", "71", "81", "91"), back)

        assertEquals(19, door19.size)
        assertTrue(door19.contains("11"))
        assertEquals(1, door19.count { it == "11" })

        val reverseUnion = NumberExpansionEngine.expandReverseUnion(listOf("31", "11"))
        assertEquals(listOf("11", "13", "31"), reverseUnion)
    }

    @Test
    fun fixtureEdit_excludesOldMinorAndMatchesExpected90000WithinLimit100000() {
        // ข้อมูลตาม 07_TEST_FIXTURES.json ("edit"):
        // other_minor="60000", old_minor="20000", replacement_minor="30000", limit_minor="100000", expected_minor="90000"
        val result = PolicyAndCapEngine.evaluateBillEditCapCheck(
            otherActiveBillsMinor = ExactMoneyMath.parsePositiveMinorUnits("60000"),
            oldActiveBillRevisionMinor = ExactMoneyMath.parsePositiveMinorUnits("20000"),
            replacementRevisionMinor = ExactMoneyMath.parsePositiveMinorUnits("30000"),
            receiveCapLimitMinor = ExactMoneyMath.parsePositiveMinorUnits("100000")
        )

        assertEquals(90_000L, result.expectedNewTotalMinor)
        assertTrue(result.isAllowedWithinCap)
        assertEquals(10_000L, result.remainingCapMinor)
    }

    @Test
    fun fixtureCash01_matchesClosingMinus2000000_andShortfall3000000() {
        // ข้อมูลตาม 07_TEST_FIXTURES.json ("CASH01"):
        // opening="6000000", inflow="1000000", outflow="9000000", closing="-2000000", minimum="1000000", shortfall="3000000"
        val cashResult = PolicyAndCapEngine.evaluateCashScenarioPosition(
            openingCashMinor = ExactMoneyMath.parseSignedMinorUnits("6000000"),
            availableInflowsMinor = ExactMoneyMath.parseSignedMinorUnits("1000000"),
            horizonDueOutflowsMinor = ExactMoneyMath.parseSignedMinorUnits("9000000"),
            minimumRequiredCashMinor = ExactMoneyMath.parseSignedMinorUnits("1000000")
        )

        assertEquals(-2_000_000L, cashResult.closingCashMinor)
        assertEquals(3_000_000L, cashResult.shortfallMinor)
        assertTrue(cashResult.hasShortfallAlert)
    }

    @Test
    fun exactMoneyMath_roundsEachPersistedLineHalfUpOnce_thenSums() {
        // ทดสอบการปัดเศษ HALF_UP รายบรรทัดครั้งเดียวก่อนรวมยอด:
        // สมมติยอดซื้อ 1 สตางค์ คูณอัตราจ่าย 60.5 -> 60.5 ปัด HALF_UP ได้ 61 สตางค์ต่อบรรทัด
        // หากมี 2 บรรทัด ต้องได้ 61 + 61 = 122 สตางค์ (ไม่ใช่รวมก่อนแล้วคูณได้ 121)
        val rate = ExactMoneyMath.parseExactRate("60.5")
        val line1Payout = ExactMoneyMath.computeProvisionalLinePayoutMinor(1L, rate)
        val line2Payout = ExactMoneyMath.computeProvisionalLinePayoutMinor(1L, rate)
        val sumOfRoundedLines = ExactMoneyMath.checkedAdd(line1Payout, line2Payout)

        assertEquals(61L, line1Payout)
        assertEquals(61L, line2Payout)
        assertEquals(122L, sumOfRoundedLines)
    }

    @Test
    fun policyInheritance_preservesExplicitOverrideFields_andEnforcesSeparateReceiveAndPayoutCaps() {
        val defaults = DomainPolicyDefaults(
            policyVersion = 2L,
            defaultTopRateExact = "65",
            defaultBottomRateExact = "65",
            defaultReceiveCapMinor = 100_000L,
            defaultPayoutCapMinor = 3_000_000L
        )
        // ตั้ง Override เฉพาะอัตราจ่ายของเลข 31 บน เป็น "50" แต่ปล่อยเพดานรับเป็น null เพื่อสืบทอดจากค่ามาตรฐาน
        val override31Top = DomainNumberPolicyOverride(
            numberTwoDigit = "31",
            side = BetSide.TOP,
            overrideRateExact = "50",
            overrideReceiveCapMinor = null,
            overridePayoutCapMinor = 1_000_000L,
            overrideIsClosed = null
        )

        val resolved = PolicyAndCapEngine.resolveEffectiveNumberSidePolicy(
            numberTwoDigit = "31",
            side = BetSide.TOP,
            defaultPolicy = defaults,
            explicitOverride = override31Top
        )

        assertEquals("50", resolved.effectiveRateExact)
        assertTrue(resolved.hasRateOverride)
        assertEquals(100_000L, resolved.effectiveReceiveCapMinor)
        assertFalse(resolved.hasReceiveCapOverride)
        assertEquals(1_000_000L, resolved.effectivePayoutCapMinor)
        assertTrue(resolved.hasPayoutCapOverride)

        // ทดสอบกรณีที่ยอดซื้อไม่เกิน Receive Cap (25,000 <= 100,000) แต่ยอดจ่ายคาดการณ์เกิน Payout Cap (25,000 * 50 = 1,250,000 > 1,000,000)
        val quoteResult = PolicyAndCapEngine.evaluateAndQuoteOrderLines(
            rawLines = listOf(DomainOrderLineInput("31", BetSide.TOP, 25_000L)),
            defaultPolicy = defaults,
            overridesByKey = mapOf(("31" to BetSide.TOP) to override31Top),
            currentActiveCountersByKey = mapOf(
                ("31" to BetSide.TOP) to DomainActiveCounterSnapshot("31", BetSide.TOP, 0L, 0L)
            )
        )
        assertTrue(quoteResult is QuoteEvaluationOutcome.Rejected)
        assertEquals("PAYOUT_CAP_EXCEEDED", (quoteResult as QuoteEvaluationOutcome.Rejected).code)
    }

    @Test
    fun pasteBatchStrict_blocksEntireBatchWhenSingleLineInvalid_andAcceptsValidBatch() {
        val invalidBatch = """
            31 บน 100
            999 บน 50
            27 ล่าง 200
        """.trimIndent()

        val blocked = PasteBatchParserAndQuoteHasher.parsePasteBatchStrict(invalidBatch)
        assertTrue(blocked is PasteBatchParseResult.BlockedBatch)
        val errors = (blocked as PasteBatchParseResult.BlockedBatch).errors
        assertEquals(1, errors.size)
        assertEquals(2, errors.single().lineNumber)

        val validBatch = """
            31 บน 100
            31 ล่าง 50
            27 ล่าง 200
        """.trimIndent()
        val accepted = PasteBatchParserAndQuoteHasher.parsePasteBatchStrict(validBatch)
        assertTrue(accepted is PasteBatchParseResult.AcceptedBatch)
        val lines = (accepted as PasteBatchParseResult.AcceptedBatch).lines
        assertEquals(3, lines.size)
        assertEquals(10_000L, lines[0].amountMinor)
        assertEquals(5_000L, lines[1].amountMinor)
        assertEquals(20_000L, lines[2].amountMinor)
    }

    @Test(expected = DomainArithmeticOverflowException::class)
    fun checkedAdd_whenExceedingLongMaxValue_throwsDeterministicOverflowException() {
        ExactMoneyMath.checkedAdd(Long.MAX_VALUE, 1L)
    }

    @Test(expected = InvalidMoneyAmountException::class)
    fun parseThbToMinorUnits_whenMoreThanTwoDecimalPlaces_rejectsWithoutSilentTruncation() {
        ExactMoneyMath.parseThbToMinorUnits("100.555")
    }
}
