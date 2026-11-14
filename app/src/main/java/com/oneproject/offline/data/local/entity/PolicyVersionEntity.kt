package com.oneproject.offline.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ตารางรุ่นนโยบายและอัตราจ่ายตามข้อกำหนด 04_SPEC และ 06_DECISIONS (D05)
 * - จำนวนเงินทั้งหมดเก็บเป็น Long หน่วยย่อย (1 THB = 100 สตางค์)
 * - อัตราจ่ายเก็บเป็น String ของ BigDecimal แม่นยำสูง (ไม่ใช้ Double/Float)
 * - การปัดเศษกำหนดชัดเจนเป็น HALF_UP รายบรรทัดที่บันทึก
 */
@Entity(
    tableName = "policy_versions",
    foreignKeys = [
        ForeignKey(
            entity = RoundEntity::class,
            parentColumns = ["round_id"],
            childColumns = ["round_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["round_id", "policy_version"], unique = true)
    ]
)
data class PolicyVersionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "policy_row_id")
    val policyRowId: Long = 0L,

    @ColumnInfo(name = "round_id")
    val roundId: String,

    @ColumnInfo(name = "policy_version")
    val policyVersion: Long,

    @ColumnInfo(name = "default_top_rate_exact")
    val defaultTopRateExact: String,

    @ColumnInfo(name = "default_bottom_rate_exact")
    val defaultBottomRateExact: String,

    @ColumnInfo(name = "default_receive_cap_minor")
    val defaultReceiveCapMinor: Long,

    @ColumnInfo(name = "default_payout_cap_minor")
    val defaultPayoutCapMinor: Long,

    @ColumnInfo(name = "rounding_mode")
    val roundingMode: String,

    @ColumnInfo(name = "is_demo_default")
    val isDemoDefault: Boolean,

    @ColumnInfo(name = "change_reason")
    val changeReason: String,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long
)
