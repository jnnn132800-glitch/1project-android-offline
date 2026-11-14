package com.oneproject.offline.ui.reports

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneproject.offline.core.money.ExactMoneyMath
import com.oneproject.offline.data.local.entity.BetSide
import com.oneproject.offline.data.local.entity.ResultRevisionStatus
import com.oneproject.offline.ui.theme.DangerCrimsonBg
import com.oneproject.offline.ui.theme.DangerCrimsonText
import com.oneproject.offline.ui.theme.NavyPrimary
import com.oneproject.offline.ui.theme.OnTealContainer
import com.oneproject.offline.ui.theme.StatusClosedBg
import com.oneproject.offline.ui.theme.StatusClosedText
import com.oneproject.offline.ui.theme.StatusOpenBg
import com.oneproject.offline.ui.theme.StatusOpenText
import com.oneproject.offline.ui.theme.TealContainer
import com.oneproject.offline.ui.theme.WarningAmberBg
import com.oneproject.offline.ui.theme.WarningAmberText

/**
 * หน้าจอประกาศผลรางวัล คำนวณสิทธิได้รับรางวัลแบบ Idempotent และรายงานเจาะลึก (Phase 07)
 *
 * กฎ 01, 04_SPEC และ 05_UI:
 * - บังคับ Preview ก่อน Approve เสมอ และรองรับการแก้ไขผลรางวัลด้วย Result Revision (รุ่นเดิมถูกเปลี่ยนสถานะเป็น SUPERSEDED)
 * - แสดงป้ายกำกับชัดเจนทุกจุดว่า "สิทธิได้รับรางวัล (Winning Entitlement) ยังไม่ถือว่าจ่ายเงินสดจริง"
 * - รองรับการเจาะลึก (Drilldown) ทั้งระดับบิล/บรรทัด และระดับเลข 00–99 (บน/ล่าง)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportsAndResultScreen(
    viewModel: ReportsAndResultViewModel
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
            text = "รายงาน • ประกาศผลรางวัลและเจาะลึกสิทธิได้รับรางวัล (งวด ${state.roundCode})",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        // แบนเนอร์เตือนหลักการบัญชีตามกฎข้อ 01 & 04_SPEC
        Surface(
            color = WarningAmberBg,
            contentColor = WarningAmberText,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "ข้อกำหนดสำคัญ: สิทธิได้รับรางวัล (Winning Entitlement) ≠ จ่ายเงินสดจริง (Paid Cash)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "การอนุมัติผลรางวัลจะคำนวณเฉพาะ 'สิทธิได้รับรางวัลตามผลที่อนุมัติ' จากบิลที่ใช้งานอยู่ (ACTIVE) เท่านั้น ไม่ถือว่าได้จ่ายเงินสดจริงจนกว่าจะมีการบันทึกจ่ายและจัดสรรยอดในสมุดบัญชีแยกประเภท (Phase 08)",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // แถบเลือกเมนูย่อย 3 แท็บ
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            ReportsSubTab.entries.forEach { tab ->
                FilterChip(
                    selected = state.selectedTab == tab,
                    onClick = { viewModel.selectSubTab(tab) },
                    label = { Text(tab.titleTh) }
                )
            }
        }

        if (state.statusBannerTh != null) {
            Surface(
                color = StatusOpenBg,
                contentColor = StatusOpenText,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = state.statusBannerTh ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        if (state.errorBannerTh != null) {
            Surface(
                color = DangerCrimsonBg,
                contentColor = DangerCrimsonText,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = state.errorBannerTh ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        when (state.selectedTab) {
            ReportsSubTab.RESULT_APPROVAL -> ResultPreviewAndApprovalTab(
                state = state,
                viewModel = viewModel
            )

            ReportsSubTab.ROUND_AND_BILL_DRILLDOWN -> RoundSummaryAndBillDrilldownTab(
                state = state,
                viewModel = viewModel
            )

            ReportsSubTab.NUMBER_SIDE_DRILLDOWN -> NumberSideDrilldownTab(
                state = state,
                viewModel = viewModel
            )
        }
    }
}

@Composable
private fun ResultPreviewAndApprovalTab(
    state: ReportsAndResultUiState,
    viewModel: ReportsAndResultViewModel
) {
    val activeResult = state.reportSnapshot?.activeResultRevision

    // การ์ดแสดงผลรางวัลที่อนุมัติอยู่ในปัจจุบัน
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "สถานะผลรางวัลที่อนุมัติปัจจุบันของงวด ${state.roundCode}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (activeResult == null) {
                Text(
                    text = "สถานะ: ยังไม่มีการอนุมัติผลรางวัลในงวดนี้ (กรุณากรอกเลข 2 ตัวบน/ล่าง แล้วกดคำนวณตัวอย่างก่อนอนุมัติ)",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Surface(
                    color = TealContainer,
                    contentColor = OnTealContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "ผลรางวัลที่ใช้อยู่: รุ่นที่ #${activeResult.revisionNumber} [อนุมัติผลแล้ว • APPROVED]",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "เลข 2 ตัวบน = ${activeResult.winningTopTwoDigit} • เลข 2 ตัวล่าง = ${activeResult.winningBottomTwoDigit}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "สิทธิได้รับรางวัลรวม (ยังไม่ถือว่าจ่ายเงินสดจริง): ${
                                ExactMoneyMath.formatMinorToThbString(
                                    state.reportSnapshot.totalEntitlementPayoutMinor
                                )
                            } บาท (${state.reportSnapshot.winningLineCount} บรรทัด จาก ${state.reportSnapshot.winningBillCount} บิล)",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "เหตุผลที่อนุมัติ: ${activeResult.reasonTh} (รหัสอนุมัติ: ${activeResult.approvalId ?: "-"})",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    // การ์ดกรอกเลขรางวัล -> Preview -> Approve
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "ประกาศหรือแก้ไขผลรางวัล (Preview -> Approve Result Revision)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = state.winningTopInput,
                    onValueChange = viewModel::updateWinningTopInput,
                    label = { Text("เลขรางวัล 2 ตัวบน (00–99)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )

                OutlinedTextField(
                    value = state.winningBottomInput,
                    onValueChange = viewModel::updateWinningBottomInput,
                    label = { Text("เลขรางวัล 2 ตัวล่าง (00–99)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            OutlinedTextField(
                value = state.approvalReasonInput,
                onValueChange = viewModel::updateApprovalReasonInput,
                label = { Text("เหตุผลการอนุมัติหรือแก้ไขผลรางวัล (จำเป็นต้องระบุ)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = viewModel::previewResultEntitlementAsync,
                enabled = state.winningTopInput.length == 2 && state.winningBottomInput.length == 2,
                colors = ButtonDefaults.buttonColors(containerColor = NavyPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "ปุ่มคำนวณตัวอย่างผลรางวัลและสิทธิได้รับรางวัล"
                    }
            ) {
                Text("1. คำนวณตัวอย่างผลรางวัล (Preview Entitlement)")
            }

            val preview = state.previewSummary
            if (preview != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Surface(
                    color = StatusOpenBg,
                    contentColor = StatusOpenText,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "ผลการคำนวณตัวอย่าง (Preview): บน = ${preview.winningTopTwoDigit} • ล่าง = ${preview.winningBottomTwoDigit}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "• บิลที่ใช้งานอยู่ (ACTIVE): ${preview.activeBillCount} บิล (ยอดรับรวม ${ExactMoneyMath.formatMinorToThbString(preview.totalActiveReceiveMinor)} บาท) • บิลที่ยกเลิกแล้ว (ไม่นับ): ${preview.cancelledBillCount} บิล",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "• ถูกรางวัล 2 ตัวบน (${preview.winningTopTwoDigit}): ยอดซื้อที่ถูก ${ExactMoneyMath.formatMinorToThbString(preview.topWinningPrincipalMinor)} บาท -> สิทธิได้รับรางวัลบน ${ExactMoneyMath.formatMinorToThbString(preview.topEntitlementPayoutMinor)} บาท",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "• ถูกรางวัล 2 ตัวล่าง (${preview.winningBottomTwoDigit}): ยอดซื้อที่ถูก ${ExactMoneyMath.formatMinorToThbString(preview.bottomWinningPrincipalMinor)} บาท -> สิทธิได้รับรางวัลล่าง ${ExactMoneyMath.formatMinorToThbString(preview.bottomEntitlementPayoutMinor)} บาท",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "• รวมสิทธิได้รับรางวัลทั้งสิ้น (Winning Entitlement • ยังไม่ถือว่าจ่ายเงินสดจริง): ${ExactMoneyMath.formatMinorToThbString(preview.totalEntitlementPayoutMinor)} บาท (${preview.totalEntitlementPayoutMinor} สตางค์ • ${preview.winningLineCount} บรรทัด จาก ${preview.winningBillCount} บิล)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Button(
                            onClick = viewModel::approvePreviewedResultRevisionAsync,
                            enabled = state.approvalReasonInput.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = StatusOpenText),
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    contentDescription = "ปุ่มยืนยันอนุมัติผลรางวัลลงฐานข้อมูลเครื่อง"
                                }
                        ) {
                            Text("2. ยืนยันอนุมัติผลรางวัลลงฐานข้อมูลเครื่อง (Approve Result Revision)")
                        }
                    }
                }
            }
        }
    }

    // การ์ดประวัติรุ่นการประกาศผลรางวัล (Result Revision History)
    val allRevisions = state.reportSnapshot?.allResultRevisions.orEmpty()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "ประวัติรุ่นการประกาศผลรางวัลในงวดนี้ (${allRevisions.size} รุ่น)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (allRevisions.isEmpty()) {
                Text(
                    text = "ยังไม่มีประวัติการประกาศผลรางวัลในงวดนี้",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                allRevisions.forEach { rev ->
                    val isApproved = rev.status == ResultRevisionStatus.APPROVED.name
                    val badgeBg = if (isApproved) StatusOpenBg else StatusClosedBg
                    val badgeFg = if (isApproved) StatusOpenText else StatusClosedText
                    val statusLabel = if (isApproved) {
                        ResultRevisionStatus.APPROVED.thaiLabel
                    } else {
                        ResultRevisionStatus.SUPERSEDED.thaiLabel
                    }

                    Surface(
                        color = badgeBg.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "รุ่นที่ #${rev.revisionNumber} • บน=${rev.winningTopTwoDigit} / ล่าง=${rev.winningBottomTwoDigit} [$statusLabel • ${rev.status}]",
                                style = MaterialTheme.typography.labelLarge,
                                color = badgeFg,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "รหัสรุ่น: ${rev.resultRevisionId} • รหัสอนุมัติ: ${rev.approvalId ?: "-"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "เหตุผล: ${rev.reasonTh}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundSummaryAndBillDrilldownTab(
    state: ReportsAndResultUiState,
    viewModel: ReportsAndResultViewModel
) {
    val snapshot = state.reportSnapshot
    if (snapshot == null) {
        Text("กำลังโหลดข้อมูลรายงาน...")
        return
    }

    // การ์ดสรุปภาพรวมงวดและสถานะสิทธิได้รับรางวัลเทียบกับเงินสดจ่ายจริง
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "สรุปยอดประจำงวด ${state.roundCode} (Authoritative Room Summary)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "• จำนวนบิลที่ใช้งานอยู่ (ACTIVE): ${snapshot.activeBillCount} บิล • บิลที่ยกเลิกแล้ว (CANCELLED): ${snapshot.cancelledBillCount} บิล",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "• ยอดรับสุทธิของบิลที่ใช้งานอยู่: ${ExactMoneyMath.formatMinorToThbString(snapshot.totalActiveReceiveMinor)} บาท (${snapshot.totalActiveReceiveMinor} สตางค์)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = "• สิทธิได้รับรางวัลรวมตามผลที่อนุมัติ (Winning Entitlement • ยังไม่ถือว่าจ่ายเงินสดจริง): ${ExactMoneyMath.formatMinorToThbString(snapshot.totalEntitlementPayoutMinor)} บาท (${snapshot.winningLineCount} บรรทัด จาก ${snapshot.winningBillCount} บิล)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = NavyPrimary
            )
            Text(
                text = "• ยอดที่บันทึกจ่ายเงินสดแล้ว (Allocated Paid Cash): ${ExactMoneyMath.formatMinorToThbString(snapshot.totalAllocatedPaidCashMinor)} บาท",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "• ยอดสิทธิได้รับรางวัลคงค้างที่ยังไม่ได้จ่ายเงินสด (Unpaid Entitlement Balance): ${ExactMoneyMath.formatMinorToThbString(snapshot.unpaidEntitlementBalanceMinor)} บาท",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    // การ์ดเจาะลึกรายบิลที่เลือกอยู่ (ถ้ามี)
    val drilldown = state.selectedBillDrilldown
    if (drilldown != null) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, NavyPrimary, RoundedCornerShape(10.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "เจาะลึกบิล ${drilldown.bill.humanBillCode} (Active Revision: ${drilldown.activeRevisionId})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedButton(onClick = { viewModel.selectBillForDrilldownAsync(null) }) {
                        Text("ปิดหน้าต่างเจาะลึก")
                    }
                }
                Text(
                    text = "หมายเหตุลูกค้า: ${drilldown.bill.customerNoteTh.ifBlank { "-" }} • ยอดซื้อรวม: ${ExactMoneyMath.formatMinorToThbString(drilldown.bill.currentTotalAmountMinor)} บาท",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "สิทธิได้รับรางวัลของบิลนี้ (ยังไม่ถือว่าจ่ายเงินสดจริง): ${ExactMoneyMath.formatMinorToThbString(drilldown.totalEntitlementPayoutMinor)} บาท • จ่ายแล้ว: ${ExactMoneyMath.formatMinorToThbString(drilldown.totalAllocatedPaidCashMinor)} บาท • คงค้าง: ${ExactMoneyMath.formatMinorToThbString(drilldown.unpaidEntitlementBalanceMinor)} บาท",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
                HorizontalDivider()
                drilldown.lines.forEach { line ->
                    val sideTh = if (line.side == BetSide.TOP.name) "บน" else "ล่าง"
                    val rowBg = if (line.isWinningLine) StatusOpenBg else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    Surface(
                        color = rowBg,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "บรรทัดที่ #${line.lineNumber}: เลข ${line.numberTwoDigit} ($sideTh) • ยอดซื้อ ${ExactMoneyMath.formatMinorToThbString(line.principalAmountMinor)} บาท × อัตราจ่าย ${line.rateSnapshotExact}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            if (line.isWinningLine) {
                                Text(
                                    text = "สถานะ: ถูกรางวัล! สิทธิได้รับรางวัล = ${ExactMoneyMath.formatMinorToThbString(line.entitlementPayoutMinor)} บาท (ยังไม่ถือว่าจ่ายเงินสดจริง • คงค้างรอจ่าย ${ExactMoneyMath.formatMinorToThbString(line.unpaidEntitlementBalanceMinor)} บาท)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StatusOpenText,
                                    fontWeight = FontWeight.SemiBold
                                )
                            } else {
                                Text(
                                    text = "สถานะ: ไม่ถูกรางวัลในรุ่นผลรางวัลปัจจุบัน (ยอดจ่ายประมาณการตอนรับบิล ${ExactMoneyMath.formatMinorToThbString(line.provisionalPayoutMinor)} บาท)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // รายการบิลสำหรับกดเจาะลึก
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = state.onlyWinningBillsFilter,
                    onCheckedChange = viewModel::setOnlyWinningBillsFilter
                )
                Text(
                    text = "แสดงเฉพาะบิลที่มีสิทธิได้รับรางวัล (Winning Bills Only)",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            val displayedBills = if (state.onlyWinningBillsFilter) {
                snapshot.billSummaries.filter { it.winningLineCount > 0 }
            } else {
                snapshot.billSummaries
            }

            if (displayedBills.isEmpty()) {
                Text(
                    text = "ไม่พบบิลตามเงื่อนไขที่เลือก",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                displayedBills.forEach { item ->
                    val isWinner = item.winningLineCount > 0
                    Surface(
                        color = if (isWinner) StatusOpenBg.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectBillForDrilldownAsync(item.billId) }
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "${item.humanBillCode} • ยอดซื้อ ${ExactMoneyMath.formatMinorToThbString(item.totalPrincipalMinor)} บาท (${item.customerNoteTh.ifBlank { "ไม่มีหมายเหตุ" }})",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isWinner) {
                                    "ถูกรางวัล ${item.winningLineCount} บรรทัด • สิทธิได้รับรางวัล ${ExactMoneyMath.formatMinorToThbString(item.totalEntitlementPayoutMinor)} บาท (ยังไม่ถือว่าจ่ายเงินสดจริง • คงค้าง ${ExactMoneyMath.formatMinorToThbString(item.unpaidEntitlementBalanceMinor)} บาท) [แตะเพื่อเจาะลึกรายบรรทัด]"
                                } else {
                                    "สิทธิได้รับรางวัล: 0.00 บาท [แตะเพื่อเจาะลึกรายบรรทัด]"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NumberSideDrilldownTab(
    state: ReportsAndResultUiState,
    viewModel: ReportsAndResultViewModel
) {
    val snapshot = state.reportSnapshot
    if (snapshot == null) {
        Text("กำลังโหลดข้อมูลรายเลข...")
        return
    }

    val selectedDrilldown = state.selectedNumberSideDrilldown
    if (selectedDrilldown != null) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, NavyPrimary, RoundedCornerShape(10.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "เจาะลึกเลข ${selectedDrilldown.numberTwoDigit} (${selectedDrilldown.side.thaiLabel})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedButton(
                        onClick = { viewModel.selectNumberSideForDrilldownAsync(null, null) }
                    ) {
                        Text("ปิดหน้าต่างเจาะลึก")
                    }
                }
                Text(
                    text = "ยอดรับสะสมสุทธิ: ${ExactMoneyMath.formatMinorToThbString(selectedDrilldown.totalPrincipalMinor)} บาท • ยอดจ่ายประมาณการ: ${ExactMoneyMath.formatMinorToThbString(selectedDrilldown.totalProvisionalPayoutMinor)} บาท • สิทธิได้รับรางวัล (ยังไม่ถือว่าจ่ายจริง): ${ExactMoneyMath.formatMinorToThbString(selectedDrilldown.totalEntitlementPayoutMinor)} บาท",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                HorizontalDivider()
                selectedDrilldown.contributingLines.forEach { row ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "บิล ${row.humanBillCode} (บรรทัดที่ #${row.lineNumber}) • ยอดซื้อ ${ExactMoneyMath.formatMinorToThbString(row.principalAmountMinor)} บาท × อัตรา ${row.rateSnapshotExact}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (row.isWinningEntitlement) {
                                    "สิทธิได้รับรางวัล: ${ExactMoneyMath.formatMinorToThbString(row.entitlementPayoutMinor)} บาท (ยังไม่ถือว่าจ่ายเงินสดจริง)"
                                } else {
                                    "ยอดจ่ายประมาณการหากถูกรางวัล: ${ExactMoneyMath.formatMinorToThbString(row.provisionalPayoutMinor)} บาท"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "ตารางสรุปยอดรับและสิทธิรางวัลรายเลข 00–99 (${snapshot.numberSideRows.size} รายการที่มีการซื้อ)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (snapshot.numberSideRows.isEmpty()) {
                Text(
                    text = "ยังไม่มีรายการซื้อที่ยืนยันแล้วในงวดนี้",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                snapshot.numberSideRows.forEach { row ->
                    val sideEnum = BetSide.entries.firstOrNull { it.name == row.side } ?: BetSide.TOP
                    Surface(
                        color = if (row.isWinningOutcome) StatusOpenBg.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.selectNumberSideForDrilldownAsync(row.numberTwoDigit, sideEnum)
                            }
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "เลข ${row.numberTwoDigit} (${sideEnum.thaiLabel}) ${if (row.isWinningOutcome) "• [ตรงผลรางวัลที่อนุมัติ]" else ""}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "ยอดรับสะสม: ${ExactMoneyMath.formatMinorToThbString(row.activeReceiveTotalMinor)} บาท • ยอดจ่ายประมาณการ: ${ExactMoneyMath.formatMinorToThbString(row.activeProvisionalPayoutTotalMinor)} บาท",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (row.isWinningOutcome) {
                                Text(
                                    text = "สิทธิได้รับรางวัลรวม (${row.winningLineCount} บรรทัด • ยังไม่ถือว่าจ่ายเงินสดจริง): ${ExactMoneyMath.formatMinorToThbString(row.winningEntitlementTotalMinor)} บาท [แตะเพื่อดูบิลที่มา]",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StatusOpenText,
                                    fontWeight = FontWeight.SemiBold
                                )
                            } else {
                                Text(
                                    text = "[แตะเพื่อเจาะลึกดูรายการบิลที่ซื้อเลขนี้]",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
