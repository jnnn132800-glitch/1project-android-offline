package com.oneproject.offline

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.oneproject.offline.ui.MainViewModel
import com.oneproject.offline.ui.OneProjectApp
import com.oneproject.offline.ui.backup.BackupAndRestoreViewModel
import com.oneproject.offline.ui.bills.BillsAndPolicyViewModel
import com.oneproject.offline.ui.counterparty.CounterpartyTransferViewModel
import com.oneproject.offline.ui.ledger.LedgerAndReconciliationViewModel
import com.oneproject.offline.ui.order.OrderEntryViewModel
import com.oneproject.offline.ui.reports.ReportsAndResultViewModel
import com.oneproject.offline.ui.risk.RiskDashboardViewModel
import com.oneproject.offline.ui.security.SecurityAndDeliveryViewModel
import com.oneproject.offline.ui.theme.OneProjectTheme

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels {
        val app = application as OneProjectApplication
        MainViewModel.factory(
            repository = app.bootstrapRepository,
            useCase = app.ensureFirstLaunchBootstrapUseCase
        )
    }

    private val riskDashboardViewModel: RiskDashboardViewModel by viewModels {
        val app = application as OneProjectApplication
        RiskDashboardViewModel.factory(
            bootstrapRepository = app.bootstrapRepository,
            ledgerRepository = app.ledgerAndReconciliationRepository
        )
    }

    private val orderEntryViewModel: OrderEntryViewModel by viewModels {
        val app = application as OneProjectApplication
        OrderEntryViewModel.factory(
            bootstrapRepository = app.bootstrapRepository,
            orderRepository = app.orderRepository
        )
    }

    private val billsAndPolicyViewModel: BillsAndPolicyViewModel by viewModels {
        val app = application as OneProjectApplication
        BillsAndPolicyViewModel.factory(
            bootstrapRepository = app.bootstrapRepository,
            orderRepository = app.orderRepository
        )
    }

    private val reportsAndResultViewModel: ReportsAndResultViewModel by viewModels {
        val app = application as OneProjectApplication
        ReportsAndResultViewModel.factory(
            bootstrapRepository = app.bootstrapRepository,
            orderRepository = app.orderRepository
        )
    }

    private val ledgerAndReconciliationViewModel: LedgerAndReconciliationViewModel by viewModels {
        val app = application as OneProjectApplication
        LedgerAndReconciliationViewModel.factory(
            bootstrapRepository = app.bootstrapRepository,
            ledgerRepository = app.ledgerAndReconciliationRepository
        )
    }

    private val counterpartyTransferViewModel: CounterpartyTransferViewModel by viewModels {
        val app = application as OneProjectApplication
        CounterpartyTransferViewModel.factory(
            bootstrapRepository = app.bootstrapRepository,
            ledgerRepository = app.ledgerAndReconciliationRepository
        )
    }

    private val backupAndRestoreViewModel: BackupAndRestoreViewModel by viewModels {
        val app = application as OneProjectApplication
        BackupAndRestoreViewModel.factory(
            backupManager = app.backupAndRestoreManager,
            bootstrapRepository = app.bootstrapRepository
        )
    }

    private val securityAndDeliveryViewModel: SecurityAndDeliveryViewModel by viewModels {
        val app = application as OneProjectApplication
        SecurityAndDeliveryViewModel.factory(
            ledgerRepository = app.ledgerAndReconciliationRepository,
            storageDirectoryProvider = { app.filesDir }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OneProjectTheme {
                OneProjectApp(
                    viewModel = mainViewModel,
                    riskDashboardViewModel = riskDashboardViewModel,
                    orderEntryViewModel = orderEntryViewModel,
                    billsAndPolicyViewModel = billsAndPolicyViewModel,
                    reportsAndResultViewModel = reportsAndResultViewModel,
                    ledgerAndReconciliationViewModel = ledgerAndReconciliationViewModel,
                    counterpartyTransferViewModel = counterpartyTransferViewModel,
                    backupAndRestoreViewModel = backupAndRestoreViewModel,
                    securityAndDeliveryViewModel = securityAndDeliveryViewModel
                )
            }
        }
    }
}
