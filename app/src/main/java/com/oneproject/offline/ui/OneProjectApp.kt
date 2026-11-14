package com.oneproject.offline.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.oneproject.offline.core.money.ExactMoneyMath
import com.oneproject.offline.data.local.entity.RoundStatus
import com.oneproject.offline.ui.backup.BackupAndRestoreScreen
import com.oneproject.offline.ui.backup.BackupAndRestoreViewModel
import com.oneproject.offline.ui.bills.BillsAndPolicyScreen
import com.oneproject.offline.ui.bills.BillsAndPolicyViewModel
import com.oneproject.offline.ui.counterparty.CounterpartyTransferScreen
import com.oneproject.offline.ui.counterparty.CounterpartyTransferViewModel
import com.oneproject.offline.ui.ledger.LedgerAndReconciliationScreen
import com.oneproject.offline.ui.ledger.LedgerAndReconciliationViewModel
import com.oneproject.offline.ui.navigation.OneProjectDestination
import com.oneproject.offline.ui.order.OrderEntryScreen
import com.oneproject.offline.ui.order.OrderEntryViewModel
import com.oneproject.offline.ui.reports.ReportsAndResultScreen
import com.oneproject.offline.ui.reports.ReportsAndResultViewModel
import com.oneproject.offline.ui.risk.RiskDashboardScreen
import com.oneproject.offline.ui.risk.RiskDashboardViewModel
import com.oneproject.offline.ui.security.SecurityAndDeliveryScreen
import com.oneproject.offline.ui.security.SecurityAndDeliveryViewModel
import com.oneproject.offline.ui.theme.NavyPrimary
import com.oneproject.offline.ui.theme.OnTealContainer
import com.oneproject.offline.ui.theme.StatusClosedBg
import com.oneproject.offline.ui.theme.StatusClosedText
import com.oneproject.offline.ui.theme.StatusDraftBg
import com.oneproject.offline.ui.theme.StatusDraftText
import com.oneproject.offline.ui.theme.StatusOpenBg
import com.oneproject.offline.ui.theme.StatusOpenText
import com.oneproject.offline.ui.theme.StatusPausedBg
import com.oneproject.offline.ui.theme.StatusPausedText
import com.oneproject.offline.ui.theme.TealContainer
import com.oneproject.offline.ui.theme.WarningAmberBg
import com.oneproject.offline.ui.theme.WarningAmberText

enum class MoreTabSubSection(val labelTh: String) {
    LEDGER_PHASE_08("สมุดบัญชีคู่ & กระทบยอดเงินสด (Phase 08)"),
    COUNTERPARTY_PHASE_10("คู่ค้า & โอนความเสี่ยง (Phase 10)"),
    BACKUP_PHASE_06B("สำรองและกู้คืนข้อมูล (Phase 06B)"),
    SECURITY_PHASE_11_12("ความปลอดภัย & ส่งมอบ (Phase 11–12)")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OneProjectApp(
    viewModel: MainViewModel,
    riskDashboardViewModel: RiskDashboardViewModel,
    orderEntryViewModel: OrderEntryViewModel,
    billsAndPolicyViewModel: BillsAndPolicyViewModel,
    reportsAndResultViewModel: ReportsAndResultViewModel,
    ledgerAndReconciliationViewModel: LedgerAndReconciliationViewModel,
    counterpartyTransferViewModel: CounterpartyTransferViewModel,
    backupAndRestoreViewModel: BackupAndRestoreViewModel,
    securityAndDeliveryViewModel: SecurityAndDeliveryViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: OneProjectDestination.OVERVIEW.route
    var selectedMoreSection by rememberSaveable { mutableStateOf(MoreTabSubSection.LEDGER_PHASE_08) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            OneProjectDestination.entries.forEach { destination ->
                val selected = currentRoute == destination.route
                item(
                    selected = selected,
                    onClick = {
                        if (currentRoute != destination.route) {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    icon = {
                        Icon(
                            imageVector = destination.icon,
                            contentDescription = destination.talkBackDescription
                        )
                    },
                    label = {
                        Text(text = destination.titleTh)
                    }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                OfflineAuthoritativeHeader(state = uiState)
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = OneProjectDestination.OVERVIEW.route,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                composable(OneProjectDestination.OVERVIEW.route) {
                    RiskDashboardScreen(viewModel = riskDashboardViewModel)
                }
                composable(OneProjectDestination.ORDER_ENTRY.route) {
                    OrderEntryScreen(viewModel = orderEntryViewModel)
                }
                composable(OneProjectDestination.BILLS.route) {
                    BillsAndPolicyScreen(viewModel = billsAndPolicyViewModel)
                }
                composable(OneProjectDestination.REPORTS.route) {
                    ReportsAndResultScreen(viewModel = reportsAndResultViewModel)
                }
                composable(OneProjectDestination.MORE.route) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            MoreTabSubSection.entries.forEach { section ->
                                FilterChip(
                                    selected = selectedMoreSection == section,
                                    onClick = { selectedMoreSection = section },
                                    label = { Text(section.labelTh) }
                                )
                            }
                        }
                        HorizontalDivider()
                        when (selectedMoreSection) {
                            MoreTabSubSection.LEDGER_PHASE_08 -> {
                                LedgerAndReconciliationScreen(
                                    viewModel = ledgerAndReconciliationViewModel
                                )
                            }
                            MoreTabSubSection.COUNTERPARTY_PHASE_10 -> {
                                CounterpartyTransferScreen(
                                    viewModel = counterpartyTransferViewModel
                                )
                            }
                            MoreTabSubSection.BACKUP_PHASE_06B -> {
                                BackupAndRestoreScreen(
                                    viewModel = backupAndRestoreViewModel,
                                    roundCode = uiState.roundCode,
                                    auditCount = uiState.auditCount
                                )
                            }
                            MoreTabSubSection.SECURITY_PHASE_11_12 -> {
                                SecurityAndDeliveryScreen(
                                    viewModel = securityAndDeliveryViewModel
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * กฎ 05_UI:
 * Header แสดงงวดและข้อมูลในเครื่อง ไม่แสดงสถานะออนไลน์ปลอม
 * สถานะมีข้อความกำกับเสมอ ไม่ใช้สีล้วน
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OfflineAuthoritativeHeader(
    state: ShellUiState
) {
    val (statusBg, statusFg) = when (state.roundStatus) {
        RoundStatus.OPEN -> StatusOpenBg to StatusOpenText
        RoundStatus.DRAFT -> StatusDraftBg to StatusDraftText
        RoundStatus.PAUSED -> StatusPausedBg to StatusPausedText
        RoundStatus.CLOSED -> StatusClosedBg to StatusClosedText
    }

    Surface(
        color = NavyPrimary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Column {
                    Text(
                        text = "1Project • งวด ${state.roundCode}",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    if (state.roundTitleTh.isNotBlank()) {
                        Text(
                            text = state.roundTitleTh,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                        )
                    }
                }

                Surface(
                    color = statusBg,
                    contentColor = statusFg,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.semantics {
                        contentDescription = "สถานะงวดปัจจุบัน ${state.roundStatus.thaiLabel}"
                    }
                ) {
                    Text(
                        text = "สถานะงวด: ${state.roundStatus.thaiLabel} [${state.roundStatus.statusCodeText}]",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    color = TealContainer,
                    contentColor = OnTealContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "ข้อมูลในเครื่อง (Single-Device Room WAL) • ไม่ใช้เซิร์ฟเวอร์",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Surface(
                    color = WarningAmberBg,
                    contentColor = WarningAmberText,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    val modeLabel = if (state.isDemoDefaultPolicy) {
                        "นโยบายรุ่น #${state.policyVersion} (DEMO DEFAULT • ห้ามใช้กับข้อมูลเงินจริง)"
                    } else {
                        "นโยบายรุ่น #${state.policyVersion}"
                    }
                    Text(
                        text = modeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun OverviewSkeletonScreen(state: ShellUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "ภาพรวมและความเสี่ยงในเครื่อง",
            style = MaterialTheme.typography.headlineSmall
        )
        InfoSectionCard(
            title = "สถานะฐานข้อมูล Room ในเครื่อง",
            lines = listOf(
                "รหัสงวดปัจจุบัน: ${state.roundCode} (${state.roundStatus.thaiLabel})",
                "รุ่นนโยบาย (Policy Version): #${state.policyVersion} (อัตราจ่ายสาธิต บน=${state.topRateExact} / ล่าง=${state.bottomRateExact})",
                "เพดานรับสาธิตต่อเลข: ${ExactMoneyMath.formatMinorToThbString(state.receiveCapMinor)} บาท (${state.receiveCapMinor} สตางค์)",
                "จำนวนบันทึก Audit Log ในเครื่อง: ${state.auditCount} รายการ",
                "การเปิดใช้งานครั้งแรกในโหมดเครื่องบิน: ${if (state.firstLaunchSeededNow) "สร้างข้อมูลตั้งต้นสำเร็จในรอบนี้" else "อ่านข้อมูลเดิมจาก Room สำเร็จ (ไม่รีเซ็ตทับ)"}"
            )
        )
    }
}

@Composable
private fun InfoSectionCard(
    title: String,
    lines: List<String>
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(10.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            lines.forEach { line ->
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
