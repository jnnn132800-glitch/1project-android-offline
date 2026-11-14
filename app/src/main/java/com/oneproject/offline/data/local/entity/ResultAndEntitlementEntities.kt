package com.oneproject.offline.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ตารางรุ่นการประกาศผลรางวัล (Result Revision) และสิทธิได้รับเงินรางวัล (Winning Entitlement)
 * กฎข้อ 01 & 04_SPEC:
 * - การประกาศผลต้องผ่านการตรวจทานและอนุมัติ (Preview -> Approve) และรองรับการแก้ไขด้วย Revision
 * - สิทธิได้รับเงินรางวัล (Winning Entitlement) ไม่เท่ากับว่าจ่ายเงินสดแล้ว (winningentitlement != paid)
 */
@Entity(
    tableName = "result_revisions",
    foreignKeys = [
        ForeignKey(
            entity = RoundEntity::class,
            parentColumns = ["round_id"],
            childColumns = ["round_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["round_id", "revision_number"], unique = true),
        Index(value = ["round_id", "status"])
    ]
)
data class ResultRevisionEntity(
    @PrimaryKey
    @ColumnInfo(name = "result_revision_id")
    val resultRevisionId: String,

    @ColumnInfo(name = "round_id")
    val roundId: String,

    @ColumnInfo(name = "revision_number")
    val revisionNumber: Int,

    @ColumnInfo(name = "winning_top_two_digit")
    val winningTopTwoDigit: String,

    @ColumnInfo(name = "winning_bottom_two_digit")
    val winningBottomTwoDigit: String,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "approval_id")
    val approvalId: String?,

    @ColumnInfo(name = "reason_th")
    val reasonTh: String,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long
)

@Entity(
    tableName = "winning_entitlements",
    foreignKeys = [
        ForeignKey(
            entity = ResultRevisionEntity::class,
            parentColumns = ["result_revision_id"],
            childColumns = ["result_revision_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = BillEntity::class,
            parentColumns = ["bill_id"],
            childColumns = ["bill_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = BillRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["bill_revision_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["result_revision_id", "bill_line_id"], unique = true),
        Index(value = ["round_id", "bill_id"]),
        Index(value = ["bill_revision_id"])
    ]
)
data class WinningEntitlementEntity(
    @PrimaryKey
    @ColumnInfo(name = "entitlement_id")
    val entitlementId: String,

    @ColumnInfo(name = "round_id")
    val roundId: String,

    @ColumnInfo(name = "result_revision_id")
    val resultRevisionId: String,

    @ColumnInfo(name = "bill_id")
    val billId: String,

    @ColumnInfo(name = "bill_revision_id")
    val billRevisionId: String,

    @ColumnInfo(name = "bill_line_id")
    val billLineId: Long,

    @ColumnInfo(name = "number_two_digit")
    val numberTwoDigit: String,

    @ColumnInfo(name = "side")
    val side: String,

    @ColumnInfo(name = "principal_amount_minor")
    val principalAmountMinor: Long,

    @ColumnInfo(name = "rate_snapshot_exact")
    val rateSnapshotExact: String,

    @ColumnInfo(name = "entitlement_payout_minor")
    val entitlementPayoutMinor: Long,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long
)
