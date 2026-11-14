package com.oneproject.offline.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ผู้ใช้งานในเครื่อง (Local Actor) และบทบาทในเครื่อง (Local Role)
 * กฎข้อ 01: App lock / local roles ใช้ป้องกันความผิดพลาดในการปฏิบัติงานในเครื่องเดียวกัน
 * ไม่ใช่การพิสูจน์ตัวตนระยะไกล และห้ามเก็บ Plaintext PIN หรือความลับในตารางนี้
 */
@Entity(
    tableName = "local_actors",
    indices = [
        Index(value = ["actor_code"], unique = true)
    ]
)
data class LocalActorEntity(
    @PrimaryKey
    @ColumnInfo(name = "actor_id")
    val actorId: String,

    @ColumnInfo(name = "actor_code")
    val actorCode: String,

    @ColumnInfo(name = "display_name_th")
    val displayNameTh: String,

    @ColumnInfo(name = "primary_role")
    val primaryRole: String,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long
)

@Entity(
    tableName = "local_role_grants",
    foreignKeys = [
        ForeignKey(
            entity = LocalActorEntity::class,
            parentColumns = ["actor_id"],
            childColumns = ["actor_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["actor_id", "role_code"], unique = true)
    ]
)
data class LocalRoleGrantEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "grant_id")
    val grantId: Long = 0L,

    @ColumnInfo(name = "actor_id")
    val actorId: String,

    @ColumnInfo(name = "role_code")
    val roleCode: String,

    @ColumnInfo(name = "granted_at_epoch_ms")
    val grantedAtEpochMs: Long
)

/**
 * บันทึกการอนุมัติในเครื่อง (Approval Record)
 * ใช้สำหรับงานที่ต้องมีการยืนยันสิทธิ์ เช่น การประกาศผลรางวัล หรือการปรับปรุงงวดที่ปิดแล้ว (D08)
 */
@Entity(
    tableName = "approval_records",
    foreignKeys = [
        ForeignKey(
            entity = LocalActorEntity::class,
            parentColumns = ["actor_id"],
            childColumns = ["approved_by_actor_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["target_type", "target_id"]),
        Index(value = ["approved_by_actor_id"])
    ]
)
data class ApprovalRecordEntity(
    @PrimaryKey
    @ColumnInfo(name = "approval_id")
    val approvalId: String,

    @ColumnInfo(name = "target_type")
    val targetType: String,

    @ColumnInfo(name = "target_id")
    val targetId: String,

    @ColumnInfo(name = "round_id")
    val roundId: String?,

    @ColumnInfo(name = "approved_by_actor_id")
    val approvedByActorId: String,

    @ColumnInfo(name = "reason_th")
    val reasonTh: String,

    @ColumnInfo(name = "approved_at_epoch_ms")
    val approvedAtEpochMs: Long
)
