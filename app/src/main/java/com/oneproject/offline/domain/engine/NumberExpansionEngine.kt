package com.oneproject.offline.domain.engine

/**
 * กลไกกระจายเลข 2 หลักตามข้อกำหนด 05_UI, 06_DECISIONS (D02) และ 07_TEST_FIXTURES ("selection")
 * - รูดหน้า d = d0..d9 (10 เลข)
 * - รูดหลัง d = 0d..9d (10 เลข)
 * - 19 ประตู d = union unique ของรูดหน้าและรูดหลัง (19 เลข)
 * - กลับเลข = เพิ่มเลขกลับโดยคงเลขเดิมไว้และตัดเลขซ้ำ (เช่น ["31", "11"] -> ["11", "13", "31"])
 */
object NumberExpansionEngine {

    val ALL_TWO_DIGIT_NUMBERS: List<String> = (0..99).map { "%02d".format(it) }

    fun validateTwoDigitNumber(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.length == 2 && trimmed[0] in '0'..'9' && trimmed[1] in '0'..'9') {
            "รูปแบบเลข 2 หลักไม่ถูกต้อง: '$raw' (ต้องเป็น 00 ถึง 99)"
        }
        return trimmed
    }

    fun validateSingleDigit(raw: String): Char {
        val trimmed = raw.trim()
        require(trimmed.length == 1 && trimmed[0] in '0'..'9') {
            "หลักตัวเลขสำหรับรูด/19ประตูต้องเป็น 0 ถึง 9 เท่านั้น (ได้รับ: '$raw')"
        }
        return trimmed[0]
    }

    /**
     * รูดหน้า d -> d0..d9 จำนวน 10 เลข
     */
    fun expandFront(digitInput: String): List<String> {
        val d = validateSingleDigit(digitInput)
        return ('0'..'9').map { second -> "$d$second" }
    }

    /**
     * รูดหลัง d -> 0d..9d จำนวน 10 เลข
     */
    fun expandBack(digitInput: String): List<String> {
        val d = validateSingleDigit(digitInput)
        return ('0'..'9').map { first -> "$first$d" }
    }

    /**
     * 19 ประตู d -> union unique ของรูดหน้าและรูดหลัง เรียงจากน้อยไปมาก จำนวน 19 เลข
     */
    fun expandDoor19(digitInput: String): List<String> {
        val front = expandFront(digitInput)
        val back = expandBack(digitInput)
        return (front + back).toSortedSet().toList()
    }

    /**
     * สลับหลักเลข 2 หลัก เช่น "31" -> "13", "11" -> "11"
     */
    fun reverseTwoDigit(number: String): String {
        val valid = validateTwoDigitNumber(number)
        return "${valid[1]}${valid[0]}"
    }

    /**
     * กฎ D02 & 07_TEST_FIXTURES:
     * กลับเลข (Reverse Union) เพิ่มเลขกลับโดยคงเลขเดิมไว้และรวมเป็นชุดไม่ซ้ำกันเรียงตามลำดับ
     * ตัวอย่าง: ["31", "11"] -> ["11", "13", "31"]
     */
    fun expandReverseUnion(numbers: List<String>): List<String> {
        val resultSet = sortedSetOf<String>()
        for (raw in numbers) {
            val valid = validateTwoDigitNumber(raw)
            resultSet.add(valid)
            resultSet.add(reverseTwoDigit(valid))
        }
        return resultSet.toList()
    }
}
