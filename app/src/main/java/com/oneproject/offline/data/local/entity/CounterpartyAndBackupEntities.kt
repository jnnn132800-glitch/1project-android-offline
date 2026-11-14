package com.oneproject.offline.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ตารางคู่สัญญา สัญญาการรับความเสี่ยง การส่งออกด้วยมือ และข้อมูลประวัติการสำรองข้อมูล
 * กฎข้อ 01 & 04_SPEC:
 * - ปิดการโอนเงินหรือสั่งจ่ายภายนอกจริงทั้งหมด (Actual external payment/transfer execution disabled)
 * - รายการสถานะรออนุมัติ (PENDING) ไม่ลดความเสี่ยงโดยเด็ดขาด
 * - รายการที่คู่สัญญายอมรับแล้ว (ACCEPTED) ลดความเสี่ยงเฉพาะในกรณีผลรางวัลที่ตรงกับเงื่อนไขสัญญา และไม่ถือเป็นเงินสดที่ได้รับแน่นอนแล้ว
 */
@Entity(
    tableName = "counterparties",
    indices = [
        Index(value = ["counterparty_code"], unique = true)
    ]
)
data class CounterpartyEntity(
    @PrimaryKey
    @ColumnInfo(name = "counterparty_id")
    val counterpartyId: String,

    @ColumnInfo(name = "counterparty_code")
    val counterpartyCode: String,

    @ColumnInfo(name = "display_name_th")
    val displayNameTh: String,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long
)

@Entity(
    tableName = "counterparty_contracts",
    foreignKeys = [
        ForeignKey(
            entity = CounterpartyEntity::class,
            parentColumns = ["counterparty_id"],
            childColumns = ["counterparty_id"],
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
        Index(value = ["counterparty_id", "round_id"])
    ]
)
data class CounterpartyContractEntity(
    @PrimaryKey
    @ColumnInfo(name = "contract_id")
    val contractId: String,

    @ColumnInfo(name = "counterparty_id")
    val counterpartyId: String,

    @ColumnInfo(name = "round_id")
    val roundId: String,

    @ColumnInfo(name = "contract_top_rate_exact")
    val contractTopRateExact: String,

    @ColumnInfo(name = "contract_bottom_rate_exact")
    val contractBottomRateExact: String,

    @ColumnInfo(name = "max_transfer_cap_minor")
    val maxTransferCapMinor: Long,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long
)

@Entity(
    tableName = "manual_transfer_batches",
    foreignKeys = [
        ForeignKey(
            entity = CounterpartyContractEntity::class,
            parentColumns = ["contract_id"],
            childColumns = ["contract_id"],
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
        Index(value = ["round_id", "status"]),
        Index(value = ["contract_id"])
    ]
)
data class ManualTransferBatchEntity(
    @PrimaryKey
    @ColumnInfo(name = "batch_id")
    val batchId: String,

    @ColumnInfo(name = "round_id")
    val roundId: String,

    @ColumnInfo(name = "contract_id")
    val contractId: String,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "total_submitted_minor")
    val totalSubmittedMinor: Long,

    @ColumnInfo(name = "total_accepted_minor")
    val totalAcceptedMinor: Long,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long
)

@Entity(
    tableName = "manual_transfer_lines",
    foreignKeys = [
        ForeignKey(
            entity = ManualTransferBatchEntity::class,
            parentColumns = ["batch_id"],
            childColumns = ["batch_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["batch_id", "number_two_digit", "side"], unique = true)
    ]
)
data class ManualTransferLineEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "transfer_line_id")
    val transferLineId: Long = 0L,

    @ColumnInfo(name = "batch_id")
    val batchId: String,

    @ColumnInfo(name = "round_id")
    val roundId: String,

    @ColumnInfo(name = "number_two_digit")
    val numberTwoDigit: String,

    @ColumnInfo(name = "side")
    val side: String,

    @ColumnInfo(name = "submitted_amount_minor")
    val submittedAmountMinor: Long,

    @ColumnInfo(name = "accepted_amount_minor")
    val acceptedAmountMinor: Long,

    @ColumnInfo(name = "contract_rate_exact")
    val contractRateExact: String,

    @ColumnInfo(name = "accepted_recoverable_payout_minor")
    val acceptedRecoverablePayoutMinor: Long,

    @ColumnInfo(name = "line_status")
    val lineStatus: String
)

@Entity(
    tableName = "transfer_responses",
    foreignKeys = [
        ForeignKey(
            entity = ManualTransferBatchEntity::class,
            parentColumns = ["batch_id"],
            childColumns = ["batch_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["batch_id"])
    ]
)
data class TransferResponseEntity(
    @PrimaryKey
    @ColumnInfo(name = "response_id")
    val responseId: String,

    @ColumnInfo(name = "batch_id")
    val batchId: String,

    @ColumnInfo(name = "response_status")
    val responseStatus: String,

    @ColumnInfo(name = "manual_reference_note_th")
    val manualReferenceNoteTh: String,

    @ColumnInfo(name = "recorded_by_actor_id")
    val recordedByActorId: String,

    @ColumnInfo(name = "recorded_at_epoch_ms")
    val recordedAtEpochMs: Long
)

@Entity(
    tableName = "backup_metadata",
    indices = [
        Index(value = ["archive_id"], unique = true),
        Index(value = ["created_at_epoch_ms"])
    ]
)
data class BackupMetadataEntity(
    @PrimaryKey
    @ColumnInfo(name = "archive_id")
    val archiveId: String,

    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int,

    @ColumnInfo(name = "archive_format_version")
    val archiveFormatVersion: Int,

    @ColumnInfo(name = "wal_checkpoint_completed")
    val walCheckpointCompleted: Boolean,

    @ColumnInfo(name = "payload_sha256")
    val payloadSha256: String,

    @ColumnInfo(name = "action_type")
    val actionType: String,

    @ColumnInfo(name = "note_th")
    val noteTh: String,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long
)
