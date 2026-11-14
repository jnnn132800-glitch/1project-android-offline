package com.oneproject.offline.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ตารางบันทึกเหตุการณ์ตรวจสอบภายในเครื่อง (Local Authoritative Audit Log)
 * กฎข้อ 01: บันทึกใน Transaction เดียวกับการเปลี่ยนแปลงสถานะหลัก และห้ามเก็บ PIN/ความลับแบบ Plaintext
 */
@Entity(
    tableName = "audit_logs",
    indices = [
        Index(value = ["event_type"]),
        Index(value = ["created_at_epoch_ms"])
    ]
)
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "audit_id")
    val auditId: Long = 0L,

    @ColumnInfo(name = "event_type")
    val eventType: String,

    @ColumnInfo(name = "actor_role")
    val actorRole: String,

    @ColumnInfo(name = "round_id")
    val roundId: String?,

    @ColumnInfo(name = "summary_th")
    val summaryTh: String,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long
)
