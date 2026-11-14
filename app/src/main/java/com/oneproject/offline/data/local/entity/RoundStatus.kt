package com.oneproject.offline.data.local.entity

/**
 * สถานะของงวดตามข้อกำหนด 04_SPEC และ 05_UI
 * กฎ 05_UI: สถานะต้องมีข้อความกำกับเสมอ ห้ามใช้สีล้วนในการสื่อความหมาย
 */
enum class RoundStatus(
    val thaiLabel: String,
    val statusCodeText: String
) {
    DRAFT(
        thaiLabel = "ร่าง (ยังไม่เปิดรับ)",
        statusCodeText = "DRAFT"
    ),
    OPEN(
        thaiLabel = "เปิดรับรายการ",
        statusCodeText = "OPEN"
    ),
    PAUSED(
        thaiLabel = "พักรับรายการชั่วคราว",
        statusCodeText = "PAUSED"
    ),
    CLOSED(
        thaiLabel = "ปิดงวดแล้ว",
        statusCodeText = "CLOSED"
    );

    companion object {
        fun fromStorageValue(value: String): RoundStatus {
            return entries.firstOrNull { it.name == value } ?: DRAFT
        }
    }
}
