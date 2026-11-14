package com.oneproject.offline.core.money

import java.math.BigDecimal
import java.math.RoundingMode

class DomainArithmeticOverflowException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

class InvalidMoneyAmountException(
    message: String
) : IllegalArgumentException(message)

class InvalidPayoutRateException(
    message: String
) : IllegalArgumentException(message)

/**
 * กลไกคำนวณเงินและอัตราจ่ายแบบแม่นยำ (Exact Deterministic Financial Math — Phase 03)
 * กฎข้อ 01, 04_SPEC และ D05:
 * - ห้ามใช้ Double หรือ Float ในการคำนวณเงินโดยเด็ดขาด
 * - จำนวนเงินทั้งหมดเก็บและคำนวณเป็น Long หน่วยย่อย (1 THB = 100 minor units / สตางค์)
 * - อัตราจ่ายใช้ BigDecimal แม่นยำสูง ปัดเศษ RoundingMode.HALF_UP รายบรรทัดที่บันทึกเพียงครั้งเดียวก่อนนำมารวมยอด
 * - ทุกการบวก ลบ คูณ และรวมยอด (รวมถึงผลรวมจาก SQLite) ต้องตรวจเช็ค Overflow เสมอ
 */
object ExactMoneyMath {

    const val THB_MINOR_SCALE: Int = 2
    const val MINOR_UNITS_PER_BAHT: Long = 100L

    // เพดานสูงสุดทางเทคนิคต่อบรรทัดสำหรับโหมดสาธิต (100,000,000.00 บาท = 10,000,000,000 สตางค์)
    // ค่าเพดานธุรกิจจริงรอการอนุมัติใน D05
    const val DEFAULT_MAX_SINGLE_LINE_MINOR: Long = 10_000_000_000L
    val DEFAULT_MAX_PAYOUT_RATE: BigDecimal = BigDecimal("10000")

    fun checkedAdd(a: Long, b: Long): Long {
        return try {
            Math.addExact(a, b)
        } catch (e: ArithmeticException) {
            throw DomainArithmeticOverflowException(
                "เกิด Overflow ขณะบวกจำนวนเงินหน่วยสตางค์ ($a + $b เกินขอบเขต 64-bit Long)",
                e
            )
        }
    }

    fun checkedSubtract(a: Long, b: Long): Long {
        return try {
            Math.subtractExact(a, b)
        } catch (e: ArithmeticException) {
            throw DomainArithmeticOverflowException(
                "เกิด Overflow ขณะลบจำนวนเงินหน่วยสตางค์ ($a - $b เกินขอบเขต 64-bit Long)",
                e
            )
        }
    }

    fun checkedMultiplyLong(a: Long, b: Long): Long {
        return try {
            Math.multiplyExact(a, b)
        } catch (e: ArithmeticException) {
            throw DomainArithmeticOverflowException(
                "เกิด Overflow ขณะคูณจำนวนเงินหน่วยสตางค์ ($a * $b เกินขอบเขต 64-bit Long)",
                e
            )
        }
    }

    /**
     * รวมยอดรายการ Long พร้อมตรวจเช็ค Overflow ทุกขั้นตอน (ใช้ตรวจทั้งใน Domain และผลลัพธ์จาก SQLite)
     */
    fun checkedSum(values: Iterable<Long>): Long {
        var acc = 0L
        for (value in values) {
            acc = checkedAdd(acc, value)
        }
        return acc
    }

    /**
     * แปลงค่า String ของหน่วยสตางค์ (เช่น "10000" จาก 07_TEST_FIXTURES) เป็น Long ที่ผ่านการตรวจขอบเขต
     */
    fun parsePositiveMinorUnits(
        rawMinor: String,
        maxMinorBound: Long = DEFAULT_MAX_SINGLE_LINE_MINOR
    ): Long {
        val trimmed = rawMinor.trim()
        if (trimmed.isEmpty()) {
            throw InvalidMoneyAmountException("จำนวนเงินหน่วยสตางค์ห้ามว่าง")
        }
        val parsed = trimmed.toLongOrNull()
            ?: throw InvalidMoneyAmountException("รูปแบบจำนวนเงินหน่วยสตางค์ไม่ถูกต้อง: '$rawMinor'")

        if (parsed <= 0L) {
            throw InvalidMoneyAmountException("จำนวนเงินต่อรายการต้องมากกว่า 0 สตางค์ (ได้รับ: $parsed)")
        }
        if (parsed > maxMinorBound) {
            throw InvalidMoneyAmountException(
                "จำนวนเงินต่อรายการ ($parsed สตางค์) เกินเพดานสูงสุดที่อนุญาต ($maxMinorBound สตางค์)"
            )
        }
        return parsed
    }

    /**
     * แปลงจำนวนเงินหน่วยสตางค์ที่อนุญาตให้เป็นค่าลบหรือศูนย์ได้ (เช่น ยอดเงินสดคงเหลือติดลบใน CASH01)
     */
    fun parseSignedMinorUnits(rawMinor: String): Long {
        val trimmed = rawMinor.trim()
        if (trimmed.isEmpty()) {
            throw InvalidMoneyAmountException("จำนวนเงินหน่วยสตางค์ห้ามว่าง")
        }
        return trimmed.toLongOrNull()
            ?: throw InvalidMoneyAmountException("รูปแบบจำนวนเงินหน่วยสตางค์ไม่ถูกต้อง: '$rawMinor'")
    }

    /**
     * แปลงข้อความจำนวนเงินบาท (เช่น "100" หรือ "100.50") เป็นหน่วยสตางค์ (Long) โดยใช้ BigDecimal
     * ห้ามปัดเศษทิ้งเงียบๆ หากผู้ใช้กรอกทศนิยมเกิน 2 ตำแหน่ง (เช่น "10.555") จะปฏิเสธทันที
     */
    fun parseThbToMinorUnits(
        thbText: String,
        maxMinorBound: Long = DEFAULT_MAX_SINGLE_LINE_MINOR
    ): Long {
        val trimmed = thbText.trim()
        if (trimmed.isEmpty()) {
            throw InvalidMoneyAmountException("กรุณาระบุจำนวนเงิน")
        }
        val decimal = try {
            BigDecimal(trimmed)
        } catch (e: NumberFormatException) {
            throw InvalidMoneyAmountException("รูปแบบจำนวนเงินบาทไม่ถูกต้อง: '$thbText'")
        }

        if (decimal.scale() > THB_MINOR_SCALE) {
            throw InvalidMoneyAmountException(
                "จำนวนเงินบาทมีทศนิยมเกิน $THB_MINOR_SCALE ตำแหน่ง ('$thbText') ระบบไม่อนุญาตให้ตัดเศษทิ้งอัตโนมัติ"
            )
        }

        val minorBigDecimal = decimal.movePointRight(THB_MINOR_SCALE)
        val minorLong = try {
            minorBigDecimal.longValueExact()
        } catch (e: ArithmeticException) {
            throw DomainArithmeticOverflowException("จำนวนเงินบาท '$thbText' เกินขอบเขตหน่วยสตางค์", e)
        }

        if (minorLong <= 0L) {
            throw InvalidMoneyAmountException("จำนวนเงินต้องมากกว่า 0.00 บาท")
        }
        if (minorLong > maxMinorBound) {
            throw InvalidMoneyAmountException("จำนวนเงินเกินเพดานสูงสุดต่อรายการ ($maxMinorBound สตางค์)")
        }
        return minorLong
    }

    /**
     * แปลงและตรวจสอบอัตราจ่าย (Rate) ด้วย BigDecimal แม่นยำสูง
     */
    fun parseExactRate(
        rateText: String,
        maxRateBound: BigDecimal = DEFAULT_MAX_PAYOUT_RATE
    ): BigDecimal {
        val trimmed = rateText.trim()
        if (trimmed.isEmpty()) {
            throw InvalidPayoutRateException("อัตราจ่ายห้ามว่าง")
        }
        val rate = try {
            BigDecimal(trimmed)
        } catch (e: NumberFormatException) {
            throw InvalidPayoutRateException("รูปแบบอัตราจ่ายไม่ถูกต้อง: '$rateText'")
        }

        if (rate <= BigDecimal.ZERO) {
            throw InvalidPayoutRateException("อัตราจ่ายต้องมากกว่า 0 (ได้รับ: $rateText)")
        }
        if (rate > maxRateBound) {
            throw InvalidPayoutRateException("อัตราจ่าย ($rateText) เกินค่าสูงสุดที่กำหนด ($maxRateBound)")
        }
        return rate.stripTrailingZeros().let { normalized ->
            if (normalized.scale() < 0) normalized.setScale(0) else normalized
        }
    }

    /**
     * กฎข้อ 01 & 04_SPEC (D05):
     * คำนวณยอดจ่ายคาดการณ์ต่อบรรทัดที่บันทึก (Provisional Line Payout Minor Units)
     * โดยคูณ amountMinor (Long) กับ exactRate (BigDecimal) แล้วปัดเศษแบบ HALF_UP ครั้งเดียวต่อบรรทัด
     * ก่อนแปลงกลับเป็น Long ที่ตรวจเช็ค Overflow
     */
    fun computeProvisionalLinePayoutMinor(
        amountMinor: Long,
        exactRate: BigDecimal
    ): Long {
        if (amountMinor <= 0L) {
            throw InvalidMoneyAmountException("ยอดซื้อต่อบรรทัดต้องมากกว่า 0 สตางค์ (ได้รับ: $amountMinor)")
        }
        if (exactRate <= BigDecimal.ZERO) {
            throw InvalidPayoutRateException("อัตราจ่ายต้องมากกว่า 0 (ได้รับ: ${exactRate.toPlainString()})")
        }

        val exactProduct = BigDecimal.valueOf(amountMinor).multiply(exactRate)
        val roundedHalfUp = exactProduct.setScale(0, RoundingMode.HALF_UP)

        return try {
            roundedHalfUp.longValueExact()
        } catch (e: ArithmeticException) {
            throw DomainArithmeticOverflowException(
                "ยอดจ่ายต่อบรรทัดเกินขอบเขต 64-bit Long (amountMinor=$amountMinor, rate=${exactRate.toPlainString()})",
                e
            )
        }
    }

    /**
     * แปลงหน่วยสตางค์ (Long) เป็นข้อความแสดงผลหน่วยบาท (เช่น 35000 -> "350.00", -2000000 -> "-20,000.00")
     * โดยใช้การหารจำนวนเต็มและค่าเศษสตางค์ ไม่ใช้ Double/Float
     */
    fun formatMinorToThbString(amountMinor: Long): String {
        val isNegative = amountMinor < 0L
        val wholeBaht = kotlin.math.abs(amountMinor / MINOR_UNITS_PER_BAHT)
        val satang = kotlin.math.abs(amountMinor % MINOR_UNITS_PER_BAHT)
        val signPrefix = if (isNegative) "-" else ""
        return "$signPrefix%,d.%02d".format(wholeBaht, satang)
    }
}
