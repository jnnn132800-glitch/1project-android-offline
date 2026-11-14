package com.oneproject.offline

import android.app.Application
import com.oneproject.offline.data.backup.AndroidRoomBackupDatabasePort
import com.oneproject.offline.data.backup.OfflineBackupAndRestoreManager
import com.oneproject.offline.data.local.OneProjectDatabase
import com.oneproject.offline.data.repository.AuthoritativeOrderRepository
import com.oneproject.offline.data.repository.LedgerAndReconciliationRepository
import com.oneproject.offline.data.repository.OfflineBootstrapRepository
import com.oneproject.offline.domain.usecase.EnsureFirstLaunchBootstrapUseCase

/**
 * Application Container สำหรับ 1Project (Single Authoritative Offline Device)
 * ไม่มีการเรียกใช้ SDK เครือข่าย คลาวด์ หรือ AI ใดๆ ตอนเริ่มต้นแอป
 */
class OneProjectApplication : Application() {

    val database: OneProjectDatabase by lazy {
        OneProjectDatabase.getInstance(this)
    }

    val bootstrapRepository: OfflineBootstrapRepository by lazy {
        OfflineBootstrapRepository(database.bootstrapDao())
    }

    val orderRepository: AuthoritativeOrderRepository by lazy {
        AuthoritativeOrderRepository(
            cartAndQuoteDao = database.cartAndQuoteDao(),
            commitDao = database.authoritativeCommitDao()
        )
    }

    val ledgerAndReconciliationRepository: LedgerAndReconciliationRepository by lazy {
        LedgerAndReconciliationRepository(
            ledgerDao = database.ledgerAndAccountingDao(),
            commitDao = database.authoritativeCommitDao()
        )
    }

    val backupAndRestoreManager: OfflineBackupAndRestoreManager by lazy {
        OfflineBackupAndRestoreManager(
            port = AndroidRoomBackupDatabasePort(this)
        )
    }

    val ensureFirstLaunchBootstrapUseCase: EnsureFirstLaunchBootstrapUseCase by lazy {
        EnsureFirstLaunchBootstrapUseCase(bootstrapRepository)
    }
}
