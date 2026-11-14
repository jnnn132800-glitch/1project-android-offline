package com.oneproject.offline.data.local.entity

/**
 * Enum มาตรฐานสำหรับโดเมนและฐานข้อมูล Room (Phase 02)
 * กฎ 05_UI: ทุกสถานะต้องมีข้อความภาษาไทยกำกับชัดเจน ไม่ใช้สีล้วนสื่อความหมาย
 */
enum class BetSide(val thaiLabel: String) {
    TOP("บน"),
    BOTTOM("ล่าง")
}

enum class BillStatus(val thaiLabel: String) {
    ACTIVE("ใช้งานอยู่ (นับรวมในวงเงิน)"),
    CANCELLED("ยกเลิกแล้ว (ตัดออกจากวงเงินแต่เก็บประวัติ)")
}

enum class DurableOperationStatus(val thaiLabel: String) {
    COMPLETED("บันทึกสำเร็จ"),
    REJECTED("ปฏิเสธรายการ")
}

enum class ResultRevisionStatus(val thaiLabel: String) {
    PREVIEW("ร่างรอตรวจทาน"),
    APPROVED("อนุมัติผลแล้ว"),
    SUPERSEDED("ถูกแทนที่ด้วยผลรุ่นใหม่")
}

enum class TransferResponseStatus(val thaiLabel: String) {
    PENDING("รอผลตอบรับ (ไม่ลดความเสี่ยง)"),
    ACCEPTED("คู่สัญญารับแล้ว (เฉพาะงวดและผลที่ตรงสัญญา)"),
    PARTIAL("รับบางส่วน"),
    REJECTED("ปฏิเสธ")
}

enum class LedgerEntryDirection(val thaiLabel: String) {
    DEBIT("เดบิต (DEBIT)"),
    CREDIT("เครดิต (CREDIT)")
}

enum class AccountScope(val thaiLabel: String) {
    ORDER_SETTLEMENT("ขอบเขตลูกหนี้/เจ้าหนี้รายการรับ"),
    COUNTERPARTY_SETTLEMENT("ขอบเขตคู่สัญญาตีออก"),
    OPERATING_CASH("ขอบเขตเงินสดในมือ")
}
