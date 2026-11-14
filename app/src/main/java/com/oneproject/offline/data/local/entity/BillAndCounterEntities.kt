package com.oneproject.offline.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ตารางบิลหลัก (Bill Header & Active Revision Pointer)
 * กฎข้อ 01 & 04_SPEC:
 * - มีทั้ง bill_id (UUID) และ human_bill_code ที่ไม่ซ้ำกันในฐานข้อมูล (ไม่พึ่งเวลาอย่างเดียว)
 * - เก็บ active_revision_id ชี้ไปยัง Revision ปัจจุบันที่มีผลต่อวงเงิน
 * - เมื่อแก้ไขบิลจะเปลี่ยน active_revision_id ไปชี้ Revision ใหม่และตัดยอด Revision เก่าออกจาก Counter
 * - เมื่อยกเลิกบิลต้องระบุเหตุผล (cancel_reason) ห้ามลบข้อมูลทิ้ง (No hard delete)
 */
@Entity(
    tableName = "bills",
    foreignKeys = [
        ForeignKey(
            entity = RoundEntity::class,
            parentColumns = ["round_id"],
            childColumns = ["round_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["human_bill_code"], unique = true),
        Index(value = ["round_id", "status"]),
        Index(value = ["active_revision_id"])
    ]
)
data class BillEntity(
    @PrimaryKey
    @ColumnInfo(name = "bill_id")
    val billId: String,

    @ColumnInfo(name = "human_bill_code")
    val humanBillCode: String,

    @ColumnInfo(name = "round_id")
    val roundId: String,

    @ColumnInfo(name = "active_revision_id")
    val activeRevisionId: String?,

    @ColumnInfo(name = "latest_revision_number")
    val latestRevisionNumber: Int,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "customer_note_th")
    val customerNoteTh: String,

    @ColumnInfo(name = "cancel_reason_th")
    val cancelReasonTh: String?,

    @ColumnInfo(name = "cancelled_at_epoch_ms")
    val cancelledAtEpochMs: Long?,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,

    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long
)

@Entity(
    tableName = "bill_revisions",
    foreignKeys = [
        ForeignKey(
            entity = BillEntity::class,
            parentColumns = ["bill_id"],
            childColumns = ["bill_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = RoundEntity::class,
            parentColumns = ["round_id"],
            childColumns = ["round_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["bill_id", "revision_number"], unique = true),
        Index(value = ["round_id", "policy_version"]),
        Index(value = ["operation_key"], unique = true)
    ]
)
data class BillRevisionEntity(
    @PrimaryKey
    @ColumnInfo(name = "revision_id")
    val revisionId: String,

    @ColumnInfo(name = "bill_id")
    val billId: String,

    @ColumnInfo(name = "revision_number")
    val revisionNumber: Int,

    @ColumnInfo(name = "round_id")
    val roundId: String,

    @ColumnInfo(name = "policy_version")
    val policyVersion: Long,

    @ColumnInfo(name = "operation_key")
    val operationKey: String,

    @ColumnInfo(name = "total_amount_minor")
    val totalAmountMinor: Long,

    @ColumnInfo(name = "total_provisional_payout_minor")
    val totalProvisionalPayoutMinor: Long,

    @ColumnInfo(name = "line_count")
    val lineCount: Int,

    @ColumnInfo(name = "distinct_number_count")
    val distinctNumberCount: Int,

    @ColumnInfo(name = "revision_reason_th")
    val revisionReasonTh: String,

    @ColumnInfo(name = "created_by_actor_id")
    val createdByActorId: String,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long
)

/**
 * รายการเลข/ด้านที่บันทึกในแต่ละ Revision ของบิล
 * กฎข้อ 01 & 04_SPEC:
 * - บันทึก rate_snapshot_exact (String ของ BigDecimal) ณ เวลาที่ยืนยัน
 * - คำนวณ provisional_payout_minor ด้วยการปัดเศษ HALF_UP รายบรรทัดที่บันทึกเพียงครั้งเดียวก่อนนำมารวม
 */
@Entity(
    tableName = "bill_number_side_lines",
    foreignKeys = [
        ForeignKey(
            entity = BillRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["revision_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = BillEntity::class,
            parentColumns = ["bill_id"],
            childColumns = ["bill_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["revision_id", "line_number"], unique = true),
        Index(value = ["bill_id"]),
        Index(value = ["round_id", "number_two_digit", "side"])
    ]
)
data class BillNumberSideLineEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "bill_line_id")
    val billLineId: Long = 0L,

    @ColumnInfo(name = "revision_id")
    val revisionId: String,

    @ColumnInfo(name = "bill_id")
    val billId: String,

    @ColumnInfo(name = "round_id")
    val roundId: String,

    @ColumnInfo(name = "line_number")
    val lineNumber: Int,

    @ColumnInfo(name = "number_two_digit")
    val numberTwoDigit: String,

    @ColumnInfo(name = "side")
    val side: String,

    @ColumnInfo(name = "amount_minor")
    val amountMinor: Long,

    @ColumnInfo(name = "rate_snapshot_exact")
    val rateSnapshotExact: String,

    @ColumnInfo(name = "provisional_payout_minor")
    val provisionalPayoutMinor: Long,

    @ColumnInfo(name = "rounding_mode")
    val roundingMode: String
)

/**
 * ตารางตัวนับยอดสะสมที่ถือสิทธิ์ขาดต่อเลข/ด้าน (Authoritative Number-Side Counter)
 * กฎข้อ 01:
 * - ต้องอัปเดตใน Room Transaction เดียวกันกับ Bill, Revision, Active Pointer, Dedup Record และ Audit Log
 * - เวลาแก้ไขบิลต้องหักยอดของ Revision เก่าที่เคย Active ออกก่อน แล้วจึงบวกยอดของ Revision ใหม่
 *   (Edit total = otherActiveBills + replacement, NOT old + replacement)
 */
@Entity(
    tableName = "number_side_counters",
    primaryKeys = ["round_id", "number_two_digit", "side"],
    foreignKeys = [
        ForeignKey(
            entity = RoundEntity::class,
            parentColumns = ["round_id"],
            childColumns = ["round_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class NumberSideCounterEntity(
    @ColumnInfo(name = "round_id")
    val roundId: String,

    @ColumnInfo(name = "number_two_digit")
    val numberTwoDigit: String,

    @ColumnInfo(name = "side")
    val side: String,

    @ColumnInfo(name = "active_receive_total_minor")
    val activeReceiveTotalMinor: Long,

    @ColumnInfo(name = "active_payout_total_minor")
    val activePayoutTotalMinor: Long,

    @ColumnInfo(name = "active_line_count")
    val activeLineCount: Int,

    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long
)
