package com.oneproject.offline.domain.engine

import com.oneproject.offline.core.money.ExactMoneyMath
import com.oneproject.offline.data.local.entity.BetSide
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * กลไกแปลงข้อความหลายบรรทัดแบบ Block-Batch (D03) และสร้างรหัสผูกใบตรวจทาน (Quote Payload SHA-256)
 * กฎข้อ 01 & D03/D04:
 * - การวางข้อความ (Paste): หากมีบรรทัดใดผิดแม้เพียงบรรทัดเดียว ต้องบล็อกทั้งชุด (Block Batch) ห้ามตัดบรรทัดที่ผิดทิ้งเงียบๆ
 * - การสร้าง Quote Hash: ผูก roundId, policyVersion และรายการเลข/ด้าน/ยอดสตางค์/อัตราจ่าย เข้าด้วยกันอย่างตายตัว
 */
object PasteBatchParserAndQuoteHasher {

    private val whitespaceRegex = Regex("\\s+")

    /**
     * รูปแบบบรรทัดที่รองรับ: "<เลข2หลัก> <บน|ล่าง|TOP|BOTTOM> <จำนวนเงินบาท>"
     * ตัวอย่าง:
     *   31 บน 100
     *   31 ล่าง 50
     *   27 ล่าง 200
     * หากพบข้อผิดพลาดแม้บรรทัดเดียว จะคืนค่า BlockedBatch พร้อมรายการบรรทัดที่ผิดทั้งหมด
     */
    fun parsePasteBatchStrict(rawText: String): PasteBatchParseResult {
        val nonBlankLines = rawText.lines()
            .mapIndexed { idx, line -> (idx + 1) to line.trim() }
            .filter { (_, trimmed) -> trimmed.isNotEmpty() }

        if (nonBlankLines.isEmpty()) {
            return PasteBatchParseResult.BlockedBatch(
                errors = listOf(
                    PasteLineError(
                        lineNumber = 1,
                        rawLine = "",
                        reasonTh = "ข้อความที่วางว่างเปล่า กรุณาระบุรายการอย่างน้อย 1 บรรทัด"
                    )
                )
            )
        }

        val parsedLines = mutableListOf<DomainOrderLineInput>()
        val errors = mutableListOf<PasteLineError>()

        for ((lineNo, text) in nonBlankLines) {
            val tokens = text.split(whitespaceRegex)
            if (tokens.size != 3) {
                errors.add(
                    PasteLineError(
                        lineNumber = lineNo,
                        rawLine = text,
                        reasonTh = "รูปแบบไม่ถูกต้อง (ต้องเป็น: <เลข 2 หลัก> <บน/ล่าง> <จำนวนเงินบาท>)"
                    )
                )
                continue
            }

            val numberToken = tokens[0]
            val sideToken = tokens[1]
            val amountThbToken = tokens[2]

            val validNumber = try {
                NumberExpansionEngine.validateTwoDigitNumber(numberToken)
            } catch (e: IllegalArgumentException) {
                errors.add(
                    PasteLineError(
                        lineNumber = lineNo,
                        rawLine = text,
                        reasonTh = "เลข '$numberToken' ไม่ใช่เลข 2 หลัก (00–99)"
                    )
                )
                continue
            }

            val parsedSide = parseSideToken(sideToken)
            if (parsedSide == null) {
                errors.add(
                    PasteLineError(
                        lineNumber = lineNo,
                        rawLine = text,
                        reasonTh = "ด้าน '$sideToken' ไม่ถูกต้อง (ระบุได้เฉพาะ 'บน' หรือ 'ล่าง')"
                    )
                )
                continue
            }

            val amountMinor = try {
                ExactMoneyMath.parseThbToMinorUnits(amountThbToken)
            } catch (e: RuntimeException) {
                errors.add(
                    PasteLineError(
                        lineNumber = lineNo,
                        rawLine = text,
                        reasonTh = e.message ?: "จำนวนเงิน '$amountThbToken' ไม่ถูกต้อง"
                    )
                )
                continue
            }

            parsedLines.add(
                DomainOrderLineInput(
                    numberTwoDigit = validNumber,
                    side = parsedSide,
                    amountMinor = amountMinor
                )
            )
        }

        return if (errors.isNotEmpty()) {
            // กฎ D03: พบที่ผิดแม้จุดเดียว ต้องบล็อกทั้งชุด (Block Batch) ไม่รับบางส่วน
            PasteBatchParseResult.BlockedBatch(errors = errors)
        } else {
            PasteBatchParseResult.AcceptedBatch(lines = parsedLines)
        }
    }

    private fun parseSideToken(token: String): BetSide? {
        return when (token.trim().uppercase()) {
            "บน", "TOP", "T" -> BetSide.TOP
            "ล่าง", "BOTTOM", "BOT", "B" -> BetSide.BOTTOM
            else -> null
        }
    }

    /**
     * สร้างรหัส SHA-256 แบบ Deterministic เพื่อผูก Quote เข้ากับ roundId, policyVersion และทุกรายการในบิล
     */
    fun computeCanonicalQuotePayloadSha256(
        roundId: String,
        policyVersion: Long,
        quotedLines: List<QuotedPersistedLine>
    ): String {
        val canonicalBuilder = StringBuilder()
        canonicalBuilder.append("round=").append(roundId.trim())
            .append("|policy=").append(policyVersion)
            .append("|count=").append(quotedLines.size)

        for (line in quotedLines) {
            canonicalBuilder.append("|#").append(line.lineNumber)
                .append(":").append(line.numberTwoDigit)
                .append(":").append(line.side.name)
                .append(":").append(line.amountMinor)
                .append(":").append(line.rateSnapshotExact)
                .append(":").append(line.provisionalPayoutMinor)
                .append(":").append(line.roundingMode)
        }

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalBuilder.toString().toByteArray(StandardCharsets.UTF_8))

        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}

data class PasteLineError(
    val lineNumber: Int,
    val rawLine: String,
    val reasonTh: String
)

sealed interface PasteBatchParseResult {
    data class AcceptedBatch(
        val lines: List<DomainOrderLineInput>
    ) : PasteBatchParseResult

    data class BlockedBatch(
        val errors: List<PasteLineError>
    ) : PasteBatchParseResult
}
