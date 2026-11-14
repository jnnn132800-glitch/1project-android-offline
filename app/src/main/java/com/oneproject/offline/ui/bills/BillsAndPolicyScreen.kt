package com.oneproject.offline.ui.bills

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneproject.offline.core.money.ExactMoneyMath
import com.oneproject.offline.data.local.entity.BetSide
import com.oneproject.offline.data.local.entity.BillStatus
import com.oneproject.offline.data.local.entity.RoundStatus
import com.oneproject.offline.ui.theme.ErrorRedBg
import com.oneproject.offline.ui.theme.ErrorRedText
import com.oneproject.offline.ui.theme.NavyPrimary
import com.oneproject.offline.ui.theme.OnTealContainer
import com.oneproject.offline.ui.theme.StatusClosedBg
import com.oneproject.offline.ui.theme.StatusClosedText
import com.oneproject.offline.ui.theme.StatusOpenBg
import com.oneproject.offline.ui.theme.StatusOpenText
import com.oneproject.offline.ui.theme.TealContainer
import com.oneproject.offline.ui.theme.WarningAmberBg
import com.oneproject.offline.ui.theme.WarningAmberText

@Composable
fun BillsAndPolicyScreen(
    viewModel: BillsAndPolicyViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (!state.errorBannerTh.isNullOrBlank()) {
            Surface(
                color = ErrorRedBg,
                contentColor = ErrorRedText,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "แจ้งเตือนข้อผิดพลาด: ${state.errorBannerTh}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        if (!state.statusBannerTh.isNullOrBlank()) {
            Surface(
                color = TealContainer,
                contentColor = OnTealContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "สถานะ: ${state.statusBannerTh}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        val tabs = listOf(
            "1. ค้นหาบิล / แก้ไข / ยกเลิก",
            "2. ตั้งค่ามาตรฐาน / รายเลข / ประวัติ",
            "3. จัดการงวด (เปิด/พัก/ปิด)"
        )
        TabRow(selectedTabIndex = state.selectedSectionTab) {
            tabs.forEachIndexed { idx, title ->
                Tab(
                    selected = state.selectedSectionTab == idx,
                    onClick = { viewModel.selectSectionTab(idx) },
                    text = { Text(text = title) }
                )
            }
        }

        when (state.selectedSectionTab) {
            0 -> BillsSearchAndRevisionSection(
                state = state,
                onSearchQueryChange = { viewModel.searchBills(it) },
                onSelectBill = { viewModel.selectBill(it) },
                onEditLinesChange = { viewModel.updateBillEditInputs(replacementPasteText = it) },
                onEditReasonChange = { viewModel.updateBillEditInputs(editReasonTh = it) },
                onSubmitEditRevision = { viewModel.submitBillEditRevision() },
                onCancelReasonChange = { viewModel.updateBillCancelReason(it) },
                onSubmitCancelBill = { viewModel.submitBillCancel() }
            )
            1 -> PolicyDefaultsOverridesAndHistorySection(
                state = state,
                onTopRateChange = { viewModel.updateDefaultPolicyForm(topRateInput = it) },
                onBottomRateChange = { viewModel.updateDefaultPolicyForm(bottomRateInput = it) },
                onReceiveCapChange = { viewModel.updateDefaultPolicyForm(receiveCapThbInput = it) },
                onPayoutCapChange = { viewModel.updateDefaultPolicyForm(payoutCapThbInput = it) },
                onApplyAllToggle = { viewModel.updateDefaultPolicyForm(applyAllClearRateAndCapOverrides = it) },
                onResetClosedToggle = { viewModel.updateDefaultPolicyForm(resetOpenCloseOverrides = it) },
                onDefaultReasonChange = { viewModel.updateDefaultPolicyForm(reasonInput = it) },
                onSubmitDefaultPolicy = { viewModel.submitNewDefaultPolicy() },
                onOverrideNumberChange = { viewModel.updateNumberOverrideForm(numberInput = it) },
                onOverrideSideChange = { viewModel.updateNumberOverrideForm(side = it) },
                onUseExplicitRateToggle = { viewModel.updateNumberOverrideForm(useExplicitRate = it) },
                onOverrideRateChange = { viewModel.updateNumberOverrideForm(rateInput = it) },
                onUseExplicitReceiveCapToggle = { viewModel.updateNumberOverrideForm(useExplicitReceiveCap = it) },
                onOverrideReceiveCapChange = { viewModel.updateNumberOverrideForm(receiveCapThbInput = it) },
                onUseExplicitPayoutCapToggle = { viewModel.updateNumberOverrideForm(useExplicitPayoutCap = it) },
                onOverridePayoutCapChange = { viewModel.updateNumberOverrideForm(payoutCapThbInput = it) },
                onUseExplicitClosedToggle = { viewModel.updateNumberOverrideForm(useExplicitClosed = it) },
                onOverrideIsClosedToggle = { viewModel.updateNumberOverrideForm(isClosedForOrder = it) },
                onOverrideReasonChange = { viewModel.updateNumberOverrideForm(reasonInput = it) },
                onSubmitNumberOverride = { viewModel.submitPerNumberOverride() }
            )
            2 -> RoundLifecycleAndClosedAdjustmentSection(
                state = state,
                onTransitionReasonChange = { viewModel.updateRoundTransitionReason(it) },
                onTransitionStatus = { viewModel.transitionRoundStatus(it) },
                onAdjTargetBillChange = { viewModel.updateClosedRoundAdjustmentForm(targetBillId = it) },
                onAdjApproverChange = { viewModel.updateClosedRoundAdjustmentForm(approverActorId = it) },
                onAdjReasonChange = { viewModel.updateClosedRoundAdjustmentForm(reasonTh = it) },
                onSubmitClosedAdjustment = { viewModel.submitClosedRoundAdjustment() }
            )
        }
    }
}

@Composable
private fun BillsSearchAndRevisionSection(
    state: BillsAndPolicyUiState,
    onSearchQueryChange: (String) -> Unit,
    onSelectBill: (String) -> Unit,
    onEditLinesChange: (String) -> Unit,
    onEditReasonChange: (String) -> Unit,
    onSubmitEditRevision: () -> Unit,
    onCancelReasonChange: (String) -> Unit,
    onSubmitCancelBill: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "ค้นหาบิลในงวด ${state.activeRoundCode} (พบ ${state.matchingBills.size} บิล)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = state.billSearchQuery,
                onValueChange = onSearchQueryChange,
                label = { Text("ค้นหาด้วยรหัสบิล (เช่น DEMO-2026-001-B00001), UUID หรือหมายเหตุ") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (state.matchingBills.isEmpty()) {
                Text("ยังไม่มีบิลที่ตรงกับคำค้นหาในงวดนี้")
            } else {
                state.matchingBills.forEach { bill ->
                    val isSelected = state.selectedBill?.billId == bill.billId
                    val statusTh = if (bill.status == BillStatus.ACTIVE.name) {
                        "ใช้งานอยู่ (ACTIVE • Revision #${bill.latestRevisionNumber})"
                    } else {
                        "ยกเลิกแล้ว (CANCELLED • เหตุผล: ${bill.cancelReasonTh ?: "-"})"
                    }
                    Surface(
                        color = if (isSelected) TealContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectBill(bill.billId) }
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "${bill.humanBillCode} • สถานะ: $statusTh",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "UUID: ${bill.billId} • หมายเหตุ: ${bill.customerNoteTh.ifBlank { "-" }}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }

    val selected = state.selectedBill
    if (selected != null) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, NavyPrimary, RoundedCornerShape(10.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val isCancelled = selected.status == BillStatus.CANCELLED.name
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "รายละเอียดบิล: ${selected.humanBillCode}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        color = if (isCancelled) StatusClosedBg else StatusOpenBg,
                        contentColor = if (isCancelled) StatusClosedText else StatusOpenText,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = if (isCancelled) "สถานะ: ยกเลิกแล้ว [CANCELLED]" else "สถานะ: ใช้งานอยู่ [ACTIVE]",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Text("Active Revision ID: ${selected.activeRevisionId ?: "ไม่มี (บิลถูกยกเลิกแล้ว)"}")

                // รายการเลขที่มีผลในปัจจุบัน
                if (state.selectedBillActiveLines.isNotEmpty()) {
                    Text(
                        text = "รายการเลขที่มีผลในปัจจุบัน (${state.selectedBillActiveLines.size} รายการ):",
                        fontWeight = FontWeight.Bold
                    )
                    state.selectedBillActiveLines.forEach { line ->
                        val sideTh = if (line.side == BetSide.TOP.name) "บน" else "ล่าง"
                        Text(
                            text = "• #${line.lineNumber}: เลข ${line.numberTwoDigit} ($sideTh) ยอดซื้อ ${ExactMoneyMath.formatMinorToThbString(line.amountMinor)} บาท × อัตราจ่าย ${line.rateSnapshotExact} = จ่ายคาดการณ์ ${ExactMoneyMath.formatMinorToThbString(line.provisionalPayoutMinor)} บาท"
                        )
                    }
                }

                HorizontalDivider()

                // ประวัติ Revision ทั้งหมดของบิล (ไม่ถูกลบเมื่อแก้ไขหรือยกเลิก)
                Text(
                    text = "ประวัติการแก้ไขบิล (Revision History — ${state.selectedBillRevisions.size} รุ่น):",
                    fontWeight = FontWeight.Bold
                )
                state.selectedBillRevisions.forEach { rev ->
                    val isActiveRev = selected.activeRevisionId == rev.revisionId
                    Text(
                        text = "• Revision #${rev.revisionNumber} [${if (isActiveRev) "ACTIVE ปัจจุบัน" else "HISTORICAL เก็บประวัติ"}] • นโยบาย #${rev.policyVersion} • ยอดรวม ${ExactMoneyMath.formatMinorToThbString(rev.totalAmountMinor)} บาท (${rev.lineCount} รายการ / ${rev.distinctNumberCount} เลขไม่ซ้ำ) • เหตุผล: ${rev.revisionReasonTh}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (!isCancelled) {
                    HorizontalDivider()
                    Text(
                        text = "แก้ไขบิล (สร้าง Revision #${selected.latestRevisionNumber + 1} • ตัดยอด Revision เก่าออกก่อนคำนวณเพดาน ไม่นับซ้ำ):",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = state.editBillReplacementPasteText,
                        onValueChange = onEditLinesChange,
                        label = { Text("รายการใหม่ของบิล (<เลข2หลัก> <บน/ล่าง> <จำนวนเงินบาท> ต่อบรรทัด)") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = state.editBillReasonTh,
                        onValueChange = onEditReasonChange,
                        label = { Text("เหตุผลในการแก้ไขบิล (บังคับระบุ)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = onSubmitEditRevision,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ยืนยันแก้ไขบิล (สร้าง Revision ใหม่โดยไม่นับยอดเก่าซ้ำ)")
                    }

                    HorizontalDivider()
                    Text(
                        text = "ยกเลิกบิล (ต้องระบุเหตุผล • ถอนยอดออกจากวงเงินแต่เก็บประวัติ Revision ไว้):",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = state.cancelBillReasonTh,
                        onValueChange = onCancelReasonChange,
                        label = { Text("เหตุผลในการยกเลิกบิล (บังคับระบุ)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = onSubmitCancelBill,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ยืนยันยกเลิกบิล ${selected.humanBillCode}")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PolicyDefaultsOverridesAndHistorySection(
    state: BillsAndPolicyUiState,
    onTopRateChange: (String) -> Unit,
    onBottomRateChange: (String) -> Unit,
    onReceiveCapChange: (String) -> Unit,
    onPayoutCapChange: (String) -> Unit,
    onApplyAllToggle: (Boolean) -> Unit,
    onResetClosedToggle: (Boolean) -> Unit,
    onDefaultReasonChange: (String) -> Unit,
    onSubmitDefaultPolicy: () -> Unit,
    onOverrideNumberChange: (String) -> Unit,
    onOverrideSideChange: (BetSide) -> Unit,
    onUseExplicitRateToggle: (Boolean) -> Unit,
    onOverrideRateChange: (String) -> Unit,
    onUseExplicitReceiveCapToggle: (Boolean) -> Unit,
    onOverrideReceiveCapChange: (String) -> Unit,
    onUseExplicitPayoutCapToggle: (Boolean) -> Unit,
    onOverridePayoutCapChange: (String) -> Unit,
    onUseExplicitClosedToggle: (Boolean) -> Unit,
    onOverrideIsClosedToggle: (Boolean) -> Unit,
    onOverrideReasonChange: (String) -> Unit,
    onSubmitNumberOverride: () -> Unit
) {
    // 1. ตั้งค่ามาตรฐาน (Default Policy) + เปรียบเทียบค่าเดิม/ค่าใหม่ + แยก Apply-All กับ เปิด/ปิดรับ
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "ตั้งค่ามาตรฐาน (Default Policy • รุ่นปัจจุบัน #${state.activePolicyVersion})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            val cur = state.currentDefaultPolicy
            if (cur != null) {
                Surface(
                    color = WarningAmberBg,
                    contentColor = WarningAmberText,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "ค่ามาตรฐานเดิม (รุ่น #${cur.policyVersion}): บน=${cur.defaultTopRateExact}, ล่าง=${cur.defaultBottomRateExact}, เพดานรับ=${ExactMoneyMath.formatMinorToThbString(cur.defaultReceiveCapMinor)} บาท, เพดานจ่าย=${ExactMoneyMath.formatMinorToThbString(cur.defaultPayoutCapMinor)} บาท",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.defaultTopRateInput,
                    onValueChange = onTopRateChange,
                    label = { Text("อัตราจ่ายบนมาตรฐาน") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = state.defaultBottomRateInput,
                    onValueChange = onBottomRateChange,
                    label = { Text("อัตราจ่ายล่างมาตรฐาน") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.defaultReceiveCapThbInput,
                    onValueChange = onReceiveCapChange,
                    label = { Text("เพดานรับต่อเลข/ด้าน (บาท)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = state.defaultPayoutCapThbInput,
                    onValueChange = onPayoutCapChange,
                    label = { Text("เพดานจ่ายต่อเลข/ด้าน (บาท)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            // แยก Apply-All (ล้าง Override อัตราจ่าย/เพดาน) ออกจาก การรีเซ็ตสถานะเปิด/ปิดรับรายเลข ตาม 05_UI & D07
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onApplyAllToggle(!state.applyAllClearRateAndCapOverrides) }
            ) {
                Checkbox(
                    checked = state.applyAllClearRateAndCapOverrides,
                    onCheckedChange = onApplyAllToggle
                )
                Text(
                    text = "Apply-All อัตราจ่ายและเพดาน: ล้างค่า Override อัตราจ่าย/เพดานรายเลขเดิมให้กลับมาใช้ค่ามาตรฐานใหม่ทั้งหมด (หากไม่เลือกจะรักษา Override รายเลขเดิมไว้ตามกฎ D07)",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onResetClosedToggle(!state.resetOpenCloseOverrides) }
            ) {
                Checkbox(
                    checked = state.resetOpenCloseOverrides,
                    onCheckedChange = onResetClosedToggle
                )
                Text(
                    text = "รีเซ็ตสถานะเปิด/ปิดรับรายเลขด้วย (แยกอิสระจาก Apply-All อัตราจ่าย/เพดาน)",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            OutlinedTextField(
                value = state.defaultPolicyChangeReasonInput,
                onValueChange = onDefaultReasonChange,
                label = { Text("เหตุผลในการเปลี่ยนนโยบายมาตรฐาน (บังคับระบุ • สร้าง Policy รุ่นใหม่ไม่ทับประวัติ)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = onSubmitDefaultPolicy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("บันทึกนโยบายมาตรฐานรุ่นใหม่ (#${state.activePolicyVersion + 1})")
            }
        }
    }

    // 2. ตั้งค่ารายเลข/ด้าน (Per-Field Explicit Inheritance vs Override)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "ตั้งค่ารายเลข/ด้าน (แยกการสืบทอด Inheritance รายช่อง: อัตราจ่าย / เพดานรับ / เพดานจ่าย / เปิด-ปิดรับ)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.overrideNumberInput,
                    onValueChange = { if (it.length <= 2) onOverrideNumberChange(it) },
                    label = { Text("เลข 2 หลัก (00–99)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BetSide.entries.forEach { side ->
                        FilterChip(
                            selected = state.overrideSide == side,
                            onClick = { onOverrideSideChange(side) },
                            label = { Text(side.thaiLabel) }
                        )
                    }
                }
            }

            // ช่องที่ 1: อัตราจ่ายรายเลข
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.useExplicitRateOverride,
                    onCheckedChange = onUseExplicitRateToggle
                )
                Text(
                    text = if (state.useExplicitRateOverride) "กำหนดอัตราจ่ายเฉพาะเลขนี้ (Explicit Override)" else "อัตราจ่าย: สืบทอดจากค่ามาตรฐาน (Inherit)",
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (state.useExplicitRateOverride) {
                OutlinedTextField(
                    value = state.overrideRateInput,
                    onValueChange = onOverrideRateChange,
                    label = { Text("อัตราจ่ายเฉพาะเลข ${state.overrideNumberInput} (${state.overrideSide.thaiLabel})") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ช่องที่ 2: เพดานรับรายเลข
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.useExplicitReceiveCapOverride,
                    onCheckedChange = onUseExplicitReceiveCapToggle
                )
                Text(
                    text = if (state.useExplicitReceiveCapOverride) "กำหนดเพดานรับเฉพาะเลขนี้ (Explicit Override)" else "เพดานรับ: สืบทอดจากค่ามาตรฐาน (Inherit)",
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (state.useExplicitReceiveCapOverride) {
                OutlinedTextField(
                    value = state.overrideReceiveCapThbInput,
                    onValueChange = onOverrideReceiveCapChange,
                    label = { Text("เพดานรับเฉพาะเลขนี้ (บาท)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ช่องที่ 3: เพดานจ่ายรายเลข
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.useExplicitPayoutCapOverride,
                    onCheckedChange = onUseExplicitPayoutCapToggle
                )
                Text(
                    text = if (state.useExplicitPayoutCapOverride) "กำหนดเพดานจ่ายเฉพาะเลขนี้ (Explicit Override)" else "เพดานจ่าย: สืบทอดจากค่ามาตรฐาน (Inherit)",
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (state.useExplicitPayoutCapOverride) {
                OutlinedTextField(
                    value = state.overridePayoutCapThbInput,
                    onValueChange = onOverridePayoutCapChange,
                    label = { Text("เพดานจ่ายเฉพาะเลขนี้ (บาท)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ช่องที่ 4: เปิด/ปิดรับรายเลข
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.useExplicitClosedOverride,
                    onCheckedChange = onUseExplicitClosedToggle
                )
                Text(
                    text = if (state.useExplicitClosedOverride) "กำหนดสถานะเปิด/ปิดรับเฉพาะเลขนี้" else "สถานะเปิด/ปิดรับ: สืบทอดจากค่ามาตรฐาน (เปิดรับ)",
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (state.useExplicitClosedOverride) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = state.overrideIsClosedForOrder,
                        onCheckedChange = onOverrideIsClosedToggle
                    )
                    Text(
                        text = if (state.overrideIsClosedForOrder) "ปิดรับรายการเลขนี้ (CLOSED FOR ORDER)" else "เปิดรับรายการเลขนี้ตามปกติ (OPEN)"
                    )
                }
            }

            OutlinedTextField(
                value = state.overrideReasonInput,
                onValueChange = onOverrideReasonChange,
                label = { Text("เหตุผลในการตั้งค่ารายเลข (บังคับระบุ)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = onSubmitNumberOverride,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("บันทึกการตั้งค่ารายเลข ${state.overrideNumberInput} (${state.overrideSide.thaiLabel})")
            }

            if (state.currentOverrides.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = "รายการเลขที่มีการตั้งค่า Override ในรุ่นนโยบาย #${state.activePolicyVersion} (${state.currentOverrides.size} รายการ):",
                    fontWeight = FontWeight.Bold
                )
                state.currentOverrides.forEach { ov ->
                    val sideTh = if (ov.side == BetSide.TOP.name) "บน" else "ล่าง"
                    val rateLabel = ov.overrideRateExact ?: "สืบทอดค่ามาตรฐาน"
                    val recCapLabel = ov.overrideReceiveCapMinor?.let { "${ExactMoneyMath.formatMinorToThbString(it)} บาท" } ?: "สืบทอดค่ามาตรฐาน"
                    val payCapLabel = ov.overridePayoutCapMinor?.let { "${ExactMoneyMath.formatMinorToThbString(it)} บาท" } ?: "สืบทอดค่ามาตรฐาน"
                    val closedLabel = when (ov.overrideIsClosed) {
                        true -> "ปิดรับรายการ"
                        false -> "เปิดรับรายการ"
                        null -> "สืบทอดค่ามาตรฐาน"
                    }
                    Text(
                        text = "• เลข ${ov.numberTwoDigit} ($sideTh): อัตราจ่าย=$rateLabel | เพดานรับ=$recCapLabel | เพดานจ่าย=$payCapLabel | สถานะ=$closedLabel (เหตุผล: ${ov.overrideReasonTh})",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

    // 3. ประวัตินโยบาย (Policy Version History)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "ประวัติการเปลี่ยนนโยบายของงวด (Policy History — ${state.policyHistory.size} รุ่น):",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            state.policyHistory.forEach { pol ->
                Text(
                    text = "• รุ่น #${pol.policyVersion}: บน=${pol.defaultTopRateExact}, ล่าง=${pol.defaultBottomRateExact}, เพดานรับ=${ExactMoneyMath.formatMinorToThbString(pol.defaultReceiveCapMinor)} บาท, เพดานจ่าย=${ExactMoneyMath.formatMinorToThbString(pol.defaultPayoutCapMinor)} บาท • เหตุผล: ${pol.changeReason}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoundLifecycleAndClosedAdjustmentSection(
    state: BillsAndPolicyUiState,
    onTransitionReasonChange: (String) -> Unit,
    onTransitionStatus: (RoundStatus) -> Unit,
    onAdjTargetBillChange: (String) -> Unit,
    onAdjApproverChange: (String) -> Unit,
    onAdjReasonChange: (String) -> Unit,
    onSubmitClosedAdjustment: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "จัดการสถานะงวด ${state.activeRoundCode} (ปัจจุบัน: ${state.activeRoundStatus.thaiLabel} [${state.activeRoundStatus.statusCodeText}])",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = state.roundTransitionReasonInput,
                onValueChange = onTransitionReasonChange,
                label = { Text("เหตุผลในการเปลี่ยนสถานะงวด (บังคับระบุ)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onTransitionStatus(RoundStatus.OPEN) },
                    enabled = state.activeRoundStatus != RoundStatus.CLOSED && state.activeRoundStatus != RoundStatus.OPEN
                ) {
                    Text("เปิดรับรายการ (OPEN)")
                }
                OutlinedButton(
                    onClick = { onTransitionStatus(RoundStatus.PAUSED) },
                    enabled = state.activeRoundStatus == RoundStatus.OPEN
                ) {
                    Text("พักรับชั่วคราว (PAUSE)")
                }
                OutlinedButton(
                    onClick = { onTransitionStatus(RoundStatus.CLOSED) },
                    enabled = state.activeRoundStatus != RoundStatus.CLOSED
                ) {
                    Text("ปิดงวดถาวร (CLOSE ROUND)")
                }
            }
        }
    }

    // ส่วนบันทึกการปรับปรุงงวดที่ปิดแล้ว (Closed-Round Approved Adjustment ตาม D08)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "รายการปรับปรุงสำหรับงวดที่ปิดแล้ว (Closed-Round Approved Adjustment • กฎ D08)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "เมื่องวดอยู่ในสถานะปิดแล้ว (CLOSED) ระบบจะบล็อกการแก้ไขบิลปกติทั้งหมด การปรับปรุงต้องบันทึกผ่านรายการอนุมัติ (Approval Record) เท่านั้น",
                style = MaterialTheme.typography.bodyMedium
            )

            OutlinedTextField(
                value = state.closedAdjustmentTargetBillIdInput,
                onValueChange = onAdjTargetBillChange,
                label = { Text("รหัสบิลหรือรหัสอ้างอิงที่ต้องการปรับปรุง") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.closedAdjustmentApproverActorIdInput,
                onValueChange = onAdjApproverChange,
                label = { Text("รหัสผู้อนุมัติในเครื่อง (Approver Actor ID)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.closedAdjustmentReasonInput,
                onValueChange = onAdjReasonChange,
                label = { Text("เหตุผลและรายละเอียดการอนุมัติปรับปรุงงวดปิดแล้ว (บังคับระบุ)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = onSubmitClosedAdjustment,
                enabled = state.activeRoundStatus == RoundStatus.CLOSED,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("บันทึกการอนุมัติปรับปรุงงวดที่ปิดแล้ว (ต้องอยู่ในสถานะ CLOSED)")
            }

            if (state.roundApprovals.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = "ประวัติการอนุมัติในงวดนี้ (${state.roundApprovals.size} รายการ):",
                    fontWeight = FontWeight.Bold
                )
                state.roundApprovals.forEach { apr ->
                    Text(
                        text = "• รหัสอนุมัติ ${apr.approvalId} [${apr.targetType}] เป้าหมาย=${apr.targetId} โดย ${apr.approvedByActorId}: ${apr.reasonTh}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
