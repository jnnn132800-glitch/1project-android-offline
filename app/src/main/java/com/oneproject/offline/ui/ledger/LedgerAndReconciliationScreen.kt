package com.oneproject.offline.ui.ledger

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneproject.offline.core.money.ExactMoneyMath
import com.oneproject.offline.data.local.dao.LedgerAndAccountingDao
import com.oneproject.offline.data.local.entity.AccountScope
import com.oneproject.offline.ui.theme.DangerRedBg
import com.oneproject.offline.ui.theme.DangerRedText
import com.oneproject.offline.ui.theme.NavyPrimary
import com.oneproject.offline.ui.theme.StatusOpenBg
import com.oneproject.offline.ui.theme.StatusOpenText
import com.oneproject.offline.ui.theme.TealSecondary
import com.oneproject.offline.ui.theme.WarningAmberBg
import com.oneproject.offline.ui.theme.WarningAmberText

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LedgerAndReconciliationScreen(
    viewModel: LedgerAndReconciliationViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "สมุดบัญชีคู่ • จัดสรรยอดรับ/จ่าย • กระทบยอดเงินสด (Phase 08)",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        // แถบแจ้งเตือน D09: ผังบัญชีสาธิต
        Surface(
            color = WarningAmberBg,
            contentColor = WarningAmberText,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, WarningAmberText.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .semantics {
                    contentDescription = "คำเตือน D09 ใช้ผังบัญชีสาธิตชั่วคราว ต้องให้ผู้รับผิดชอบบัญชีอนุมัติก่อนใช้งานเงินจริง"
                }
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "สถานะผังบัญชี [DEMO DEFAULT — D09 รอผู้รับผิดชอบบัญชีอนุมัติ]",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "กฎบัญชี 04_SPEC: โพสต์บัญชีคู่สมดุล (Debit = Credit) สกุลเงิน/ขอบเขตเดียวกัน • ห้ามแก้ทับรายการที่โพสต์แล้ว (ใช้ Linked Reversal เท่านั้น) • ยอดบิล != เงินสดที่เก็บได้ • สิทธิรางวัล != เงินสดที่จ่ายแล้ว",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        state.errorBannerTh?.let { errorMsg ->
            Surface(
                color = DangerRedBg,
                contentColor = DangerRedText,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "ปฏิเสธรายการ: $errorMsg",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        state.statusBannerTh?.let { okMsg ->
            Surface(
                color = StatusOpenBg,
                contentColor = StatusOpenText,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "สำเร็จ: $okMsg",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            LedgerSubTab.entries.forEach { tab ->
                FilterChip(
                    selected = state.selectedTab == tab,
                    onClick = { viewModel.selectTab(tab) },
                    label = {
                        Text(
                            text = tab.titleTh,
                            style = MaterialTheme.typography.labelLarge
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }

            OutlinedButton(
                onClick = { viewModel.refresh() },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("รีเฟรชข้อมูลสมุดบัญชี")
            }
        }

        when (state.selectedTab) {
            LedgerSubTab.JOURNAL_AND_REVERSAL -> JournalAndReversalSection(
                state = state,
                viewModel = viewModel
            )
            LedgerSubTab.PAYMENT_AND_ALLOCATION -> PaymentAndBoundedAllocationSection(
                state = state,
                viewModel = viewModel
            )
            LedgerSubTab.CASH_RECONCILIATION -> CashReconciliationAndTrialBalanceSection(
                state = state,
                viewModel = viewModel
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JournalAndReversalSection(
    state: LedgerAndReconciliationUiState,
    viewModel: LedgerAndReconciliationViewModel
) {
    val scopedAccounts = state.accounts.filter { it.accountScope == state.selectedScopeName }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "1. โพสต์รายการบัญชีคู่สมดุลด้วยมือ (Manual Balanced Double-Entry)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "ทุกบรรทัดในธุรกรรมเดียวกันต้องอยู่ในขอบเขตบัญชี (Account Scope) และสกุลเงิน (THB) เดียวกัน และมียอดรวม DEBIT == CREDIT ทุกสตางค์",
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = "เลือกขอบเขตบัญชี (Account Scope):",
                style = MaterialTheme.typography.labelLarge
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AccountScope.entries.forEach { scope ->
                    FilterChip(
                        selected = state.selectedScopeName == scope.name,
                        onClick = { viewModel.updateManualEntryDraft(scopeName = scope.name) },
                        label = { Text(scope.name) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }

            Text(
                text = "เลือกบัญชีฝั่งเดบิต (DEBIT Account):",
                style = MaterialTheme.typography.labelLarge
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                scopedAccounts.forEach { acc ->
                    FilterChip(
                        selected = state.selectedDebitAccountId == acc.accountId,
                        onClick = { viewModel.updateManualEntryDraft(debitAccountId = acc.accountId) },
                        label = { Text("${acc.accountCode} • ${acc.accountNameTh}") },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }

            Text(
                text = "เลือกบัญชีฝั่งเครดิต (CREDIT Account):",
                style = MaterialTheme.typography.labelLarge
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                scopedAccounts.forEach { acc ->
                    FilterChip(
                        selected = state.selectedCreditAccountId == acc.accountId,
                        onClick = { viewModel.updateManualEntryDraft(creditAccountId = acc.accountId) },
                        label = { Text("${acc.accountCode} • ${acc.accountNameTh}") },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }

            OutlinedTextField(
                value = state.manualAmountMajorInput,
                onValueChange = { viewModel.updateManualEntryDraft(amountMajorInput = it) },
                label = { Text("จำนวนเงินสมดุล เดบิต = เครดิต (บาท เช่น 100.00)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = state.manualMemoThInput,
                onValueChange = { viewModel.updateManualEntryDraft(memoThInput = it) },
                label = { Text("คำอธิบายรายการบัญชี (Memo — บังคับกรอก)") },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { viewModel.postManualBalancedJournal() },
                colors = ButtonDefaults.buttonColors(containerColor = NavyPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text("โพสต์รายการบัญชีคู่สมดุลลงฐานข้อมูล Room")
            }
        }
    }

    // ส่วนกลับรายการแบบผูกอ้างอิง (Linked Reversal — ห้ามแก้ไขทับรายการที่โพสต์แล้ว)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "2. สมุดรายวันและสร้างรายการกลับบัญชี (Linked Reversal — ห้ามแก้ทับรายการเดิม)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (state.selectedOriginalTxIdForReversal != null) {
                Surface(
                    color = WarningAmberBg,
                    contentColor = WarningAmberText,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "เตรียมสร้างรายการกลับบัญชี (Linked Reversal) อ้างอิง: ${state.selectedOriginalTxIdForReversal}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        OutlinedTextField(
                            value = state.reversalReasonThInput,
                            onValueChange = {
                                viewModel.selectTransactionForReversal(
                                    transactionId = state.selectedOriginalTxIdForReversal,
                                    reversalReasonTh = it
                                )
                            },
                            label = { Text("เหตุผลการกลับรายการบัญชี (บังคับกรอก)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.postLinkedReversal() },
                                colors = ButtonDefaults.buttonColors(containerColor = TealSecondary),
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text("ยืนยันสร้าง Linked Reversal")
                            }
                            OutlinedButton(
                                onClick = { viewModel.selectTransactionForReversal(null, "") },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text("ยกเลิกการเลือก")
                            }
                        }
                    }
                }
            }

            if (state.journals.isEmpty()) {
                Text(
                    text = "ยังไม่มีรายการโพสต์บัญชีในงวดนี้",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                state.journals.forEach { journal ->
                    val statusTag = when {
                        journal.reversesTransactionId != null ->
                            "รายการกลับบัญชี (Reverses ${journal.reversesTransactionId})"
                        journal.reversedByTransactionId != null ->
                            "ถูกกลับรายการแล้วโดย ${journal.reversedByTransactionId}"
                        else -> "โพสต์ปกติ (Active Posted)"
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(8.dp)
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "${journal.transactionId} • [${journal.accountScope}] • ยอดสมดุล ${ExactMoneyMath.formatMinorToThbString(journal.totalBalancedMinor)} บาท",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "สถานะ: $statusTag • Memo: ${journal.memoTh}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            HorizontalDivider()
                            journal.lines.forEach { line ->
                                Text(
                                    text = "  #${line.entrySequence} [${line.direction}] ${line.accountCode} (${line.accountNameTh}): ${ExactMoneyMath.formatMinorToThbString(line.amountMinor)} ${line.currencyCode}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            if (journal.reversesTransactionId == null && journal.reversedByTransactionId == null) {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.selectTransactionForReversal(journal.transactionId)
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text("เลือกรายการนี้เพื่อสร้าง Linked Reversal")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaymentAndBoundedAllocationSection(
    state: LedgerAndReconciliationUiState,
    viewModel: LedgerAndReconciliationViewModel
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "1. บันทึกรับ/จ่ายเงินสดพร้อมโพสต์บัญชีคู่สมดุลอัตโนมัติ",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.selectedPaymentDirection == LedgerAndAccountingDao.PAYMENT_DIRECTION_INFLOW_COLLECTION,
                    onClick = {
                        viewModel.updatePaymentDraft(
                            paymentDirection = LedgerAndAccountingDao.PAYMENT_DIRECTION_INFLOW_COLLECTION
                        )
                    },
                    label = { Text("รับเงินสดค่าบิล (INFLOW_COLLECTION)") },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
                FilterChip(
                    selected = state.selectedPaymentDirection == LedgerAndAccountingDao.PAYMENT_DIRECTION_OUTFLOW_ENTITLEMENT,
                    onClick = {
                        viewModel.updatePaymentDraft(
                            paymentDirection = LedgerAndAccountingDao.PAYMENT_DIRECTION_OUTFLOW_ENTITLEMENT
                        )
                    },
                    label = { Text("จ่ายเงินสดรางวัล (OUTFLOW_ENTITLEMENT_PAYOUT)") },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }

            OutlinedTextField(
                value = state.paymentAmountMajorInput,
                onValueChange = { viewModel.updatePaymentDraft(amountMajorInput = it) },
                label = { Text("จำนวนเงินสดที่รับหรือจ่ายจริง (บาท เช่น 100.00)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = state.paymentEvidenceNoteThInput,
                onValueChange = { viewModel.updatePaymentDraft(evidenceNoteThInput = it) },
                label = { Text("หลักฐาน/หมายเหตุการรับ-จ่ายเงินสด (บังคับกรอก)") },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { viewModel.recordPaymentWithJournal() },
                colors = ButtonDefaults.buttonColors(containerColor = NavyPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text("บันทึกรายการรับ/จ่ายเงินสดและโพสต์สมุดบัญชี")
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "2. จัดสรรยอดเงินสดเข้าบิลหรือสิทธิรางวัลแบบมีเพดาน (Bounded Allocations)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "กฎ 04_SPEC: ห้ามจัดสรรเกินยอดคงเหลือของรายการชำระเงิน และห้ามจัดสรรเกินยอดค้างชำระจริงของบิล (BILL_RECEIVABLE) หรือสิทธิรางวัล (WINNING_ENTITLEMENT)",
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = "เลือกรายการรับ/จ่ายเงินสดที่ต้องการนำไปจัดสรร:",
                style = MaterialTheme.typography.labelLarge
            )
            if (state.payments.isEmpty()) {
                Text(
                    text = "ยังไม่มีรายการรับ/จ่ายเงินสดในงวดนี้",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.payments.forEach { pay ->
                        FilterChip(
                            selected = state.selectedPaymentIdForAllocation == pay.paymentId,
                            onClick = {
                                val nextType = if (pay.paymentDirection == LedgerAndAccountingDao.PAYMENT_DIRECTION_INFLOW_COLLECTION) {
                                    LedgerAndAccountingDao.TARGET_TYPE_BILL_RECEIVABLE
                                } else {
                                    LedgerAndAccountingDao.TARGET_TYPE_WINNING_ENTITLEMENT
                                }
                                viewModel.updateAllocationDraft(
                                    paymentId = pay.paymentId,
                                    targetType = nextType
                                )
                            },
                            label = {
                                Text(
                                    "${pay.paymentId} [${pay.paymentDirection}] • คงเหลือจัดสรร ${ExactMoneyMath.formatMinorToThbString(pay.unallocatedRemainingMinor)} / ${ExactMoneyMath.formatMinorToThbString(pay.amountMinor)} บาท"
                                )
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }
            }

            Text(
                text = "เลือกประเภทเป้าหมายการจัดสรร:",
                style = MaterialTheme.typography.labelLarge
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.selectedAllocationTargetType == LedgerAndAccountingDao.TARGET_TYPE_BILL_RECEIVABLE,
                    onClick = {
                        viewModel.updateAllocationDraft(
                            targetType = LedgerAndAccountingDao.TARGET_TYPE_BILL_RECEIVABLE,
                            targetId = state.billReceivableTargets.firstOrNull()?.billId
                        )
                    },
                    label = { Text("บิลค้างชำระ (BILL_RECEIVABLE)") },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
                FilterChip(
                    selected = state.selectedAllocationTargetType == LedgerAndAccountingDao.TARGET_TYPE_WINNING_ENTITLEMENT,
                    onClick = {
                        viewModel.updateAllocationDraft(
                            targetType = LedgerAndAccountingDao.TARGET_TYPE_WINNING_ENTITLEMENT,
                            targetId = state.winningEntitlementTargets.firstOrNull()?.entitlementId
                        )
                    },
                    label = { Text("สิทธิได้รับรางวัลค้างจ่าย (WINNING_ENTITLEMENT)") },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }

            if (state.selectedAllocationTargetType == LedgerAndAccountingDao.TARGET_TYPE_BILL_RECEIVABLE) {
                Text(
                    text = "เลือกบิลที่ยืนยันแล้ว (ยอดสั่งซื้อ != เงินสดที่เก็บได้):",
                    style = MaterialTheme.typography.labelLarge
                )
                if (state.billReceivableTargets.isEmpty()) {
                    Text(
                        text = "ยังไม่มีบิลสถานะ ACTIVE ในงวดนี้",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.billReceivableTargets.forEach { bill ->
                            FilterChip(
                                selected = state.selectedAllocationTargetId == bill.billId,
                                onClick = {
                                    viewModel.updateAllocationDraft(targetId = bill.billId)
                                },
                                label = {
                                    Text(
                                        "${bill.humanBillCode} • ยอดบิล ${ExactMoneyMath.formatMinorToThbString(bill.orderAmountMinor)} • เก็บแล้ว ${ExactMoneyMath.formatMinorToThbString(bill.collectedAllocatedMinor)} • ค้างเก็บ ${ExactMoneyMath.formatMinorToThbString(bill.uncollectedRemainingMinor)} บาท"
                                    )
                                },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = "เลือกรายการสิทธิรางวัลที่อนุมัติแล้ว (สิทธิรางวัล != เงินสดที่จ่ายแล้ว):",
                    style = MaterialTheme.typography.labelLarge
                )
                if (state.winningEntitlementTargets.isEmpty()) {
                    Text(
                        text = "ยังไม่มีรายการสิทธิรางวัลที่อนุมัติแล้วในงวดนี้ (กรุณาอนุมัติผลรางวัลในแท็บรายงานและผลรางวัลก่อน)",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.winningEntitlementTargets.forEach { ent ->
                            FilterChip(
                                selected = state.selectedAllocationTargetId == ent.entitlementId,
                                onClick = {
                                    viewModel.updateAllocationDraft(targetId = ent.entitlementId)
                                },
                                label = {
                                    Text(
                                        "${ent.entitlementId} (บิล ${ent.humanBillCode}) • สิทธิรางวัล ${ExactMoneyMath.formatMinorToThbString(ent.entitlementPayoutMinor)} • จ่ายแล้ว ${ExactMoneyMath.formatMinorToThbString(ent.paidAllocatedMinor)} • ค้างจ่าย ${ExactMoneyMath.formatMinorToThbString(ent.unpaidRemainingMinor)} บาท"
                                    )
                                },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = state.allocationAmountMajorInput,
                onValueChange = { viewModel.updateAllocationDraft(allocationAmountMajorInput = it) },
                label = { Text("จำนวนเงินที่ต้องการจัดสรรเข้าเป้าหมายนี้ (บาท)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.fillMaxRemainingAllocationForSelection() },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("คำนวณเพดานจัดสรรสูงสุดที่อนุญาต")
                }

                Button(
                    onClick = { viewModel.allocatePaymentBounded() },
                    colors = ButtonDefaults.buttonColors(containerColor = TealSecondary),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("ยืนยันจัดสรรยอดเงิน (Bounded Allocation)")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CashReconciliationAndTrialBalanceSection(
    state: LedgerAndReconciliationUiState,
    viewModel: LedgerAndReconciliationViewModel
) {
    val snap = state.reconciliationSnapshot

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "1. ตั้งค่าเงินสดต้นงวดและเงินสดนับจริงในลิ้นชัก (Cash Reconciliation Inputs)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = state.openingCashMajorInput,
                onValueChange = { viewModel.updateCashReconciliationInputs(openingCashMajorInput = it) },
                label = { Text("เงินสดสำรองต้นงวด (Opening Operating Cash — บาท)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = state.countedPhysicalCashMajorInput,
                onValueChange = { viewModel.updateCashReconciliationInputs(countedPhysicalCashMajorInput = it) },
                label = { Text("เงินสดนับจริงในลิ้นชักขณะกระทบยอด (Counted Physical Cash — บาท)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = state.minimumRequiredCashMajorInput,
                onValueChange = { viewModel.updateCashReconciliationInputs(minimumRequiredCashMajorInput = it) },
                label = { Text("เกณฑ์เงินสดขั้นต่ำที่ต้องดำรง (Minimum Required Cash — บาท)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { viewModel.recalculateCashReconciliation() },
                colors = ButtonDefaults.buttonColors(containerColor = NavyPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text("คำนวณกระทบยอดเงินสดและงบทดลองใหม่")
            }
        }
    }

    if (snap != null) {
        // ส่วนสรุปแยกยอดสั่งซื้อ vs เงินสดรับจริง และ สิทธิรางวัล vs เงินสดจ่ายจริง
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "2. แยกสถานะยอดบิล vs เงินสดรับจริง และ สิทธิรางวัล vs เงินสดจ่ายจริง",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                HorizontalDivider()
                Text(
                    text = "• ยอดบิลที่ยืนยันแล้ว (Active Order Amount): ${ExactMoneyMath.formatMinorToThbString(snap.totalActiveOrderAmountMinor)} บาท",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "• เงินสดรับที่จัดสรรเข้าบิลแล้ว (Allocated Collected Cash): ${ExactMoneyMath.formatMinorToThbString(snap.totalAllocatedToBillsMinor)} บาท",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "• ลูกหนี้ค่าบิลค้างเก็บ (Uncollected Bill Receivable): ${ExactMoneyMath.formatMinorToThbString(snap.totalUncollectedBillReceivableMinor)} บาท",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (snap.totalUncollectedBillReceivableMinor > 0L) WarningAmberText else StatusOpenText
                )
                Text(
                    text = "• เงินสดรับที่ยังไม่ได้จัดสรรเข้าบิล (Unallocated Inflow): ${ExactMoneyMath.formatMinorToThbString(snap.totalUnallocatedInflowMinor)} บาท",
                    style = MaterialTheme.typography.bodySmall
                )

                HorizontalDivider()
                Text(
                    text = "• สิทธิได้รับรางวัลที่อนุมัติแล้ว (Approved Winning Entitlement): ${ExactMoneyMath.formatMinorToThbString(snap.totalWinningEntitlementMinor)} บาท",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "• เงินสดจ่ายที่จัดสรรเข้าสิทธิรางวัลแล้ว (Allocated Paid Cash): ${ExactMoneyMath.formatMinorToThbString(snap.totalAllocatedToEntitlementsMinor)} บาท",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "• เจ้าหนี้รางวัลค้างจ่าย (Unpaid Entitlement Payable): ${ExactMoneyMath.formatMinorToThbString(snap.totalUnpaidEntitlementPayableMinor)} บาท",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (snap.totalUnpaidEntitlementPayableMinor > 0L) WarningAmberText else StatusOpenText
                )
                Text(
                    text = "• เงินสดจ่ายที่ยังไม่ได้จัดสรรเข้ารางวัล (Unallocated Outflow): ${ExactMoneyMath.formatMinorToThbString(snap.totalUnallocatedOutflowMinor)} บาท",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // ส่วนกระทบยอดเงินสดในลิ้นชัก
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "3. ผลกระทบยอดเงินสดในลิ้นชัก (Book vs Physical Cash Reconciliation)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                HorizontalDivider()
                Text(
                    text = "เงินสดต้นงวด (Opening): ${ExactMoneyMath.formatMinorToThbString(snap.openingOperatingCashMinor)} บาท",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "+ เงินสดรับรวมในงวด (Total Cash Inflows): ${ExactMoneyMath.formatMinorToThbString(snap.totalRecordedCashInflowMinor)} บาท",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "- เงินสดจ่ายรวมในงวด (Total Cash Outflows): ${ExactMoneyMath.formatMinorToThbString(snap.totalRecordedCashOutflowMinor)} บาท",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "= เงินสดคงเหลือตามบัญชี (Expected Book Closing Cash): ${ExactMoneyMath.formatMinorToThbString(snap.expectedBookClosingCashMinor)} บาท",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "เงินสดนับจริงในลิ้นชัก (Counted Physical Cash): ${ExactMoneyMath.formatMinorToThbString(snap.countedPhysicalCashMinor)} บาท",
                    style = MaterialTheme.typography.bodyMedium
                )

                val varianceLabel = when {
                    snap.physicalCashVarianceMinor == 0L -> "ตรงกันทุกสตางค์ (0.00 บาท) [RECONCILED]"
                    snap.physicalCashVarianceMinor > 0L -> "เงินสดเกินบัญชี +${ExactMoneyMath.formatMinorToThbString(snap.physicalCashVarianceMinor)} บาท [OVERAGE]"
                    else -> "เงินสดขาดบัญชี ${ExactMoneyMath.formatMinorToThbString(snap.physicalCashVarianceMinor)} บาท [SHORTAGE]"
                }
                Text(
                    text = "ผลต่างเงินสดนับจริงเทียบกับบัญชี: $varianceLabel",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )

                if (snap.hasShortfallAlert) {
                    Surface(
                        color = DangerRedBg,
                        contentColor = DangerRedText,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "แจ้งเตือนสภาพคล่อง (CASH01 Shortfall Alert): เงินสดตามบัญชีต่ำกว่าเกณฑ์ขั้นต่ำอยู่ ${ExactMoneyMath.formatMinorToThbString(snap.shortfallMinor)} บาท",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }

        // ส่วนงบทดลอง (Trial Balance)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "4. งบทดลองบัญชีคู่ (Double-Entry Trial Balance • D09 Demo Chart)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "รวมเดบิตทั้งหมด: ${ExactMoneyMath.formatMinorToThbString(snap.totalLedgerDebitMinor)} บาท • รวมเครดิตทั้งหมด: ${ExactMoneyMath.formatMinorToThbString(snap.totalLedgerCreditMinor)} บาท • สถานะสมดุล: ${if (snap.isTrialBalanceBalanced) "สมดุล [BALANCED]" else "ไม่สมดุล [UNBALANCED]"}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                HorizontalDivider()
                snap.accountTrialBalances.forEach { acc ->
                    Text(
                        text = "${acc.accountCode} [${acc.accountScope}] ${acc.accountNameTh}: Dr ${ExactMoneyMath.formatMinorToThbString(acc.totalDebitMinor)} | Cr ${ExactMoneyMath.formatMinorToThbString(acc.totalCreditMinor)} | Net(Dr-Cr) ${ExactMoneyMath.formatMinorToThbString(acc.netDebitMinusCreditMinor)} บาท",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
