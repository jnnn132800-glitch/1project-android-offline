package com.oneproject.offline.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ตารางตะกร้าที่ทนต่อการปิดแอป (Durable Local Cart)
 * กฎข้อ 01 & D03/D04:
 * - ตะกร้าไม่ใช่การจองวงเงิน (Cart is NOT reservation)
 * - รายการซ้ำในตะกร้าไม่ถูกยุบรวมเงียบๆ (Duplicate lines are preserved unless explicitly edited/removed)
 */
@Entity(
    tableName = "carts",
    foreignKeys = [
        ForeignKey(
            entity = RoundEntity::class,
            parentColumns = ["round_id"],
            childColumns = ["round_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["round_id"])
    ]
)
data class CartEntity(
    @PrimaryKey
    @ColumnInfo(name = "cart_id")
    val cartId: String,

    @ColumnInfo(name = "round_id")
    val roundId: String,

    @ColumnInfo(name = "customer_note_th")
    val customerNoteTh: String,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,

    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long
)

@Entity(
    tableName = "cart_lines",
    foreignKeys = [
        ForeignKey(
            entity = CartEntity::class,
            parentColumns = ["cart_id"],
            childColumns = ["cart_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["cart_id", "line_sequence"], unique = true)
    ]
)
data class CartLineEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "cart_line_id")
    val cartLineId: Long = 0L,

    @ColumnInfo(name = "cart_id")
    val cartId: String,

    @ColumnInfo(name = "line_sequence")
    val lineSequence: Int,

    @ColumnInfo(name = "number_two_digit")
    val numberTwoDigit: String,

    @ColumnInfo(name = "side")
    val side: String,

    @ColumnInfo(name = "amount_minor")
    val amountMinor: Long,

    @ColumnInfo(name = "entry_source")
    val entrySource: String,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long
)

/**
 * ใบเสนอราคา/ผลคำนวณหน้าตรวจทาน (Quote Snapshot)
 * กฎข้อ 01: Preview ผูกกับ payload_sha256, round_id และ policy_version
 */
@Entity(
    tableName = "quote_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = RoundEntity::class,
            parentColumns = ["round_id"],
            childColumns = ["round_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["round_id", "policy_version"]),
        Index(value = ["payload_sha256"])
    ]
)
data class QuoteSnapshotEntity(
    @PrimaryKey
    @ColumnInfo(name = "quote_id")
    val quoteId: String,

    @ColumnInfo(name = "cart_id")
    val cartId: String,

    @ColumnInfo(name = "round_id")
    val roundId: String,

    @ColumnInfo(name = "policy_version")
    val policyVersion: Long,

    @ColumnInfo(name = "payload_sha256")
    val payloadSha256: String,

    @ColumnInfo(name = "total_amount_minor")
    val totalAmountMinor: Long,

    @ColumnInfo(name = "line_count")
    val lineCount: Int,

    @ColumnInfo(name = "distinct_number_count")
    val distinctNumberCount: Int,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,

    @ColumnInfo(name = "expires_at_epoch_ms")
    val expiresAtEpochMs: Long
)

/**
 * ตารางบันทึก Operation ที่ทนทานต่อ Process Death และการกดซ้ำ (Durable Operation / Dedup Record)
 * กฎข้อ 01:
 * - หลังตรวจสิทธิ์ในเครื่อง ให้ตรวจสอบ operation_key ที่ทำสำเร็จแล้ว (Replay completed operation key)
 *   ก่อนตรวจสอบว่า Quote หมดอายุหรือไม่ (Before stale quote checks)
 * - หากใช้ operation_key เดิมแต่ payload_sha256 ต่างจากเดิม ต้องตีเป็นข้อขัดแย้ง (CONFLICT)
 * - ต้องบันทึกใน Transaction เดียวกับ Bill, Revision, Active Pointer, Authoritative Counters และ Audit Log
 */
@Entity(
    tableName = "durable_operations",
    indices = [
        Index(value = ["round_id"]),
        Index(value = ["committed_bill_id"])
    ]
)
data class DurableOperationEntity(
    @PrimaryKey
    @ColumnInfo(name = "operation_key")
    val operationKey: String,

    @ColumnInfo(name = "operation_type")
    val operationType: String,

    @ColumnInfo(name = "round_id")
    val roundId: String,

    @ColumnInfo(name = "policy_version")
    val policyVersion: Long,

    @ColumnInfo(name = "quote_id")
    val quoteId: String?,

    @ColumnInfo(name = "payload_sha256")
    val payloadSha256: String,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "committed_bill_id")
    val committedBillId: String?,

    @ColumnInfo(name = "committed_revision_id")
    val committedRevisionId: String?,

    @ColumnInfo(name = "human_bill_code")
    val humanBillCode: String?,

    @ColumnInfo(name = "rejection_code")
    val rejectionCode: String?,

    @ColumnInfo(name = "rejection_message_th")
    val rejectionMessageTh: String?,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,

    @ColumnInfo(name = "completed_at_epoch_ms")
    val completedAtEpochMs: Long
)
