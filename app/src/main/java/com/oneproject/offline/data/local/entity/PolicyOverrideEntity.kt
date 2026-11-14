package com.oneproject.offline.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ตารางตั้งค่ารายเลข/ด้าน (Per-Field Explicit Number-Side Policy Override)
 * กฎข้อ 01 & D07:
 * - การตั้งค่ารายเลขต้องแยกชัดเจนรายช่อง (Per-field override: เพดานรับ, อัตราจ่าย, สถานะเปิด/ปิดรับ)
 * - ช่องที่เป็น null หมายถึงให้สืบทอดค่าจาก PolicyVersionEntity มาตรฐาน (Inheritance)
 * - การเปลี่ยนค่ามาตรฐานใน PolicyVersion ใหม่จะไม่ทับค่าที่ตั้ง Override รายเลขไว้ ยกเว้นผู้ใช้สั่ง Apply-All อย่างชัดแจ้ง
 */
@Entity(
    tableName = "number_policy_overrides",
    foreignKeys = [
        ForeignKey(
            entity = RoundEntity::class,
            parentColumns = ["round_id"],
            childColumns = ["round_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(
            value = ["round_id", "policy_version", "number_two_digit", "side"],
            unique = true
        )
    ]
)
data class NumberPolicyOverrideEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "override_id")
    val overrideId: Long = 0L,

    @ColumnInfo(name = "round_id")
    val roundId: String,

    @ColumnInfo(name = "policy_version")
    val policyVersion: Long,

    @ColumnInfo(name = "number_two_digit")
    val numberTwoDigit: String,

    @ColumnInfo(name = "side")
    val side: String,

    @ColumnInfo(name = "override_rate_exact")
    val overrideRateExact: String?,

    @ColumnInfo(name = "override_receive_cap_minor")
    val overrideReceiveCapMinor: Long?,

    @ColumnInfo(name = "override_payout_cap_minor")
    val overridePayoutCapMinor: Long?,

    @ColumnInfo(name = "override_is_closed")
    val overrideIsClosed: Boolean?,

    @ColumnInfo(name = "override_reason_th")
    val overrideReasonTh: String,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long
)
