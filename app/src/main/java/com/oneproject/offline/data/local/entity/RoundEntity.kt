package com.oneproject.offline.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ตารางงวดการรับรายการในเครื่อง (Authoritative Local Round Entity)
 * Phase 01: โครงสร้างพื้นฐานสำหรับรองรับ First-Launch Offline Bootstrap และแสดงผลบน Header
 */
@Entity(
    tableName = "rounds",
    indices = [
        Index(value = ["round_code"], unique = true),
        Index(value = ["status"])
    ]
)
data class RoundEntity(
    @PrimaryKey
    @ColumnInfo(name = "round_id")
    val roundId: String,

    @ColumnInfo(name = "round_code")
    val roundCode: String,

    @ColumnInfo(name = "title_th")
    val titleTh: String,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "active_policy_version")
    val activePolicyVersion: Long,

    @ColumnInfo(name = "currency_code")
    val currencyCode: String,

    @ColumnInfo(name = "minor_unit_scale")
    val minorUnitScale: Int,

    @ColumnInfo(name = "is_demo_seed")
    val isDemoSeed: Boolean,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,

    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long
)
