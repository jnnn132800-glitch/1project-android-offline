package com.oneproject.offline.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.oneproject.offline.data.local.dao.AuthoritativeCommitDao
import com.oneproject.offline.data.local.dao.BootstrapDao
import com.oneproject.offline.data.local.dao.CartAndQuoteDao
import com.oneproject.offline.data.local.dao.LedgerAndAccountingDao
import com.oneproject.offline.data.local.entity.ApprovalRecordEntity
import com.oneproject.offline.data.local.entity.AuditLogEntity
import com.oneproject.offline.data.local.entity.BackupMetadataEntity
import com.oneproject.offline.data.local.entity.BillEntity
import com.oneproject.offline.data.local.entity.BillNumberSideLineEntity
import com.oneproject.offline.data.local.entity.BillRevisionEntity
import com.oneproject.offline.data.local.entity.CartEntity
import com.oneproject.offline.data.local.entity.CartLineEntity
import com.oneproject.offline.data.local.entity.CounterpartyContractEntity
import com.oneproject.offline.data.local.entity.CounterpartyEntity
import com.oneproject.offline.data.local.entity.DurableOperationEntity
import com.oneproject.offline.data.local.entity.LedgerAccountEntity
import com.oneproject.offline.data.local.entity.LedgerEntryEntity
import com.oneproject.offline.data.local.entity.LedgerTransactionEntity
import com.oneproject.offline.data.local.entity.LocalActorEntity
import com.oneproject.offline.data.local.entity.LocalRoleGrantEntity
import com.oneproject.offline.data.local.entity.ManualTransferBatchEntity
import com.oneproject.offline.data.local.entity.ManualTransferLineEntity
import com.oneproject.offline.data.local.entity.NumberPolicyOverrideEntity
import com.oneproject.offline.data.local.entity.NumberSideCounterEntity
import com.oneproject.offline.data.local.entity.PaymentAllocationEntity
import com.oneproject.offline.data.local.entity.PaymentRecordEntity
import com.oneproject.offline.data.local.entity.PolicyVersionEntity
import com.oneproject.offline.data.local.entity.QuoteSnapshotEntity
import com.oneproject.offline.data.local.entity.ResultRevisionEntity
import com.oneproject.offline.data.local.entity.RoundEntity
import com.oneproject.offline.data.local.entity.TransferResponseEntity
import com.oneproject.offline.data.local.entity.WinningEntitlementEntity
import com.oneproject.offline.data.local.migration.OneProjectMigrations

/**
 * ฐานข้อมูลหลักของเครื่อง (Single Authoritative Local Room Database — Version 2)
 * - เปิดใช้งานโหมด WRITE_AHEAD_LOGGING (WAL) เสมอเพื่อความทนทานต่อ Process Death และการสำรองข้อมูลแบบสอดคล้องกัน
 * - เปิดใช้งาน PRAGMA foreign_keys = ON ทุกครั้งที่เปิดการเชื่อมต่อ
 * - ใช้ Explicit Migration (MIGRATION_1_2) และห้ามใช้ fallbackToDestructiveMigration() โดยเด็ดขาด
 */
@Database(
    entities = [
        RoundEntity::class,
        PolicyVersionEntity::class,
        AuditLogEntity::class,
        LocalActorEntity::class,
        LocalRoleGrantEntity::class,
        ApprovalRecordEntity::class,
        NumberPolicyOverrideEntity::class,
        CartEntity::class,
        CartLineEntity::class,
        QuoteSnapshotEntity::class,
        DurableOperationEntity::class,
        BillEntity::class,
        BillRevisionEntity::class,
        BillNumberSideLineEntity::class,
        NumberSideCounterEntity::class,
        ResultRevisionEntity::class,
        WinningEntitlementEntity::class,
        LedgerAccountEntity::class,
        LedgerTransactionEntity::class,
        LedgerEntryEntity::class,
        PaymentRecordEntity::class,
        PaymentAllocationEntity::class,
        CounterpartyEntity::class,
        CounterpartyContractEntity::class,
        ManualTransferBatchEntity::class,
        ManualTransferLineEntity::class,
        TransferResponseEntity::class,
        BackupMetadataEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class OneProjectDatabase : RoomDatabase() {

    abstract fun bootstrapDao(): BootstrapDao
    abstract fun cartAndQuoteDao(): CartAndQuoteDao
    abstract fun authoritativeCommitDao(): AuthoritativeCommitDao
    abstract fun ledgerAndAccountingDao(): LedgerAndAccountingDao

    companion object {
        const val DATABASE_NAME = "oneproject_authoritative_offline.db"

        @Volatile
        private var instance: OneProjectDatabase? = null

        fun getInstance(context: Context): OneProjectDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context.applicationContext).also { created ->
                    instance = created
                }
            }
        }

        /**
         * ปิดการเชื่อมต่อ Room Database ปัจจุบันและล้าง Singleton Reference
         * ใช้เฉพาะในขั้นตอน Replace-Only Restore หลังจากทำ WAL Checkpoint เสร็จสิ้นแล้วเท่านั้น
         */
        fun closeAndResetInstance() {
            synchronized(this) {
                instance?.let { db ->
                    if (db.isOpen) {
                        db.close()
                    }
                }
                instance = null
            }
        }

        private fun buildDatabase(appContext: Context): OneProjectDatabase {
            return Room.databaseBuilder(
                appContext,
                OneProjectDatabase::class.java,
                DATABASE_NAME
            )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(OneProjectMigrations.MIGRATION_1_2)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.setForeignKeyConstraintsEnabled(true)
                    }
                })
                // กฎข้อ 01: ห้ามใช้ fallbackToDestructiveMigration() โดยเด็ดขาดเพื่อป้องกันข้อมูลหายเงียบ
                .build()
        }
    }
}
