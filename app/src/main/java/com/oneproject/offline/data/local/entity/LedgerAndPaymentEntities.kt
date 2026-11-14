package com.oneproject.offline.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ตารางผังบัญชี รายการบัญชีแยกประเภท และการชำระเงิน/จัดสรรยอด (Ledger & Payment Allocations)
 * กฎข้อ 01 & 04_SPEC:
 * - รายการบัญชีที่โพสต์แล้วต้องสมดุล (SUM(debit) == SUM(credit)) ในสกุลเงินและขอบเขตบัญชีเดียวกัน
 * - ห้ามแก้ไขทับรายการที่โพสต์แล้ว การแก้ไขต้องสร้าง Reversal Transaction ที่อ้างอิง reverses_transaction_id
 * - ยอดสั่งซื้อไม่เท่ากับเงินสดที่เก็บได้จริง (orderamount != collectedcash) และการจัดสรรยอดต้องไม่เกินยอดจริง
 */
@Entity(
    tableName = "ledger_accounts",
    indices = [
        Index(value = ["account_code"], unique = true)
    ]
)
data class LedgerAccountEntity(
    @PrimaryKey
    @ColumnInfo(name = "account_id")
    val accountId: String,

    @ColumnInfo(name = "account_code")
    val accountCode: String,

    @ColumnInfo(name = "account_name_th")
    val accountNameTh: String,

    @ColumnInfo(name = "account_scope")
    val accountScope: String,

    @ColumnInfo(name = "currency_code")
    val currencyCode: String,

    @ColumnInfo(name = "is_demo_chart")
    val isDemoChart: Boolean,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long
)

@Entity(
    tableName = "ledger_transactions",
    indices = [
        Index(value = ["round_id"]),
        Index(value = ["reverses_transaction_id"]),
        Index(value = ["operation_key"], unique = true)
    ]
)
data class LedgerTransactionEntity(
    @PrimaryKey
    @ColumnInfo(name = "transaction_id")
    val transactionId: String,

    @ColumnInfo(name = "operation_key")
    val operationKey: String,

    @ColumnInfo(name = "round_id")
    val roundId: String?,

    @ColumnInfo(name = "currency_code")
    val currencyCode: String,

    @ColumnInfo(name = "account_scope")
    val accountScope: String,

    @ColumnInfo(name = "total_balanced_minor")
    val totalBalancedMinor: Long,

    @ColumnInfo(name = "reverses_transaction_id")
    val reversesTransactionId: String?,

    @ColumnInfo(name = "memo_th")
    val memoTh: String,

    @ColumnInfo(name = "posted_by_actor_id")
    val postedByActorId: String,

    @ColumnInfo(name = "posted_at_epoch_ms")
    val postedAtEpochMs: Long
)

@Entity(
    tableName = "ledger_entries",
    foreignKeys = [
        ForeignKey(
            entity = LedgerTransactionEntity::class,
            parentColumns = ["transaction_id"],
            childColumns = ["transaction_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = LedgerAccountEntity::class,
            parentColumns = ["account_id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["transaction_id", "entry_sequence"], unique = true),
        Index(value = ["account_id"])
    ]
)
data class LedgerEntryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "entry_id")
    val entryId: Long = 0L,

    @ColumnInfo(name = "transaction_id")
    val transactionId: String,

    @ColumnInfo(name = "entry_sequence")
    val entrySequence: Int,

    @ColumnInfo(name = "account_id")
    val accountId: String,

    @ColumnInfo(name = "direction")
    val direction: String,

    @ColumnInfo(name = "amount_minor")
    val amountMinor: Long,

    @ColumnInfo(name = "currency_code")
    val currencyCode: String,

    @ColumnInfo(name = "account_scope")
    val accountScope: String
)

@Entity(
    tableName = "payment_records",
    indices = [
        Index(value = ["round_id"]),
        Index(value = ["ledger_transaction_id"])
    ]
)
data class PaymentRecordEntity(
    @PrimaryKey
    @ColumnInfo(name = "payment_id")
    val paymentId: String,

    @ColumnInfo(name = "round_id")
    val roundId: String,

    @ColumnInfo(name = "payment_direction")
    val paymentDirection: String,

    @ColumnInfo(name = "amount_minor")
    val amountMinor: Long,

    @ColumnInfo(name = "allocated_total_minor")
    val allocatedTotalMinor: Long,

    @ColumnInfo(name = "ledger_transaction_id")
    val ledgerTransactionId: String,

    @ColumnInfo(name = "evidence_note_th")
    val evidenceNoteTh: String,

    @ColumnInfo(name = "recorded_at_epoch_ms")
    val recordedAtEpochMs: Long
)

@Entity(
    tableName = "payment_allocations",
    foreignKeys = [
        ForeignKey(
            entity = PaymentRecordEntity::class,
            parentColumns = ["payment_id"],
            childColumns = ["payment_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["payment_id"]),
        Index(value = ["target_type", "target_id"])
    ]
)
data class PaymentAllocationEntity(
    @PrimaryKey
    @ColumnInfo(name = "allocation_id")
    val allocationId: String,

    @ColumnInfo(name = "payment_id")
    val paymentId: String,

    @ColumnInfo(name = "target_type")
    val targetType: String,

    @ColumnInfo(name = "target_id")
    val targetId: String,

    @ColumnInfo(name = "allocated_amount_minor")
    val allocatedAmountMinor: Long,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long
)
