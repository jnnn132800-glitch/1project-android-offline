package com.oneproject.offline.ui.order

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneproject.offline.core.money.ExactMoneyMath
import com.oneproject.offline.data.local.entity.BetSide
import com.oneproject.offline.data.local.entity.CartLineEntity
import com.oneproject.offline.ui.theme.ErrorRedBg
import com.oneproject.offline.ui.theme.ErrorRedText
import com.oneproject.offline.ui.theme.NavyPrimary
import com.oneproject.offline.ui.theme.OnTealContainer
import com.oneproject.offline.ui.theme.StatusOpenBg
import com.oneproject.offline.ui.theme.StatusOpenText
import com.oneproject.offline.ui.theme.TealContainer
import com.oneproject.offline.ui.theme.TealSecondary
import com.oneproject.offline.ui.theme.WarningAmberBg
import com.oneproject.offline.ui.theme.WarningAmberText

@Composable
fun OrderEntryScreen(
    viewModel: OrderEntryViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var lineToEdit by rememberSaveable { mutableStateOf<Long?>(null) }
    var editLineAmountInput by rememberSaveable { mutableStateOf("") }
    var manualOperationKeyLookup by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ป้ายแจ้งเตือนข้อผิดพลาดหรือสถานะการทำงาน (มีข้อความชัดเจนเสมอ ไม่ใช้สีล้วน)
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

        if (!state.feedbackBannerTh.isNullOrBlank()) {
            Surface(
                color = TealContainer,
                contentColor = OnTealContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "สถานะ: ${state.feedbackBannerTh}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // 1. หน้าสำเร็จ (แสดงเฉพาะเมื่อมี committedBillId ที่บันทึกใน Room สำเร็จแล้วเท่านั้น)
        val receipt = state.committedReceipt
        if (receipt != null && receipt.committedBillId.isNotBlank()) {
            CommittedBillSuccessCard(
                receipt = receipt,
                onDismiss = { viewModel.dismissReceipt() }
            )
        }

        // 2. หน้าตรวจทานก่อนยืนยันบันทึกในเครื่อง (Review Panel)
        val reviewQuote = state.activeReviewQuote
        if (reviewQuote != null) {
            ReviewBeforeLocalConfirmCard(
                review = reviewQuote,
                currentActivePolicyVersion = state.activePolicyVersion,
                hasPolicyChangedWarning = state.hasPolicyChangedDuringReview,
                onRecomputeQuote = { viewModel.prepareReviewQuote() },
                onCancelReview = { viewModel.cancelReviewMode() },
                onConfirmLocalCommit = { viewModel.confirmReviewAndCommit() }
            )
        }

        // 3. แท็บรับรายการ 3 รูปแบบ: กรอกเอง / เลือกหลายเลข (00–99) / วางข้อความ
        val tabTitles = listOf(
            "1. กรอกเอง",
            "2. เลือกหลายเลข (00–99)",
            "3. วางข้อความ"
        )
        TabRow(selectedTabIndex = state.selectedTabIndex) {
            tabTitles.forEachIndexed { idx, title ->
                Tab(
                    selected = state.selectedTabIndex == idx,
                    onClick = { viewModel.selectTab(idx) },
                    text = { Text(text = title) }
                )
            }
        }

        when (state.selectedTabIndex) {
            0 -> ManualOrderTabCard(
                state = state,
                onNumberChange = { viewModel.updateManualInputs(numberInput = it) },
                onSideChange = { viewModel.updateManualInputs(sideSelection = it) },
                onIncludeReverseChange = { viewModel.updateManualInputs(includeReverse = it) },
                onAmountChange = { viewModel.updateManualInputs(amountThbInput = it) },
                onAddToCart = { viewModel.submitManualEntry() }
            )
            1 -> Grid10x10OrderTabCard(
                state = state,
                onToggleNumber = { viewModel.toggleGridNumber(it) },
                onSelectAll = { viewModel.gridSelectAll100() },
                onClear = { viewModel.gridClearSelection() },
                onUndo = { viewModel.gridUndo() },
                onReverseSelected = { viewModel.gridApplyReverse() },
                onSelectDigit = { viewModel.gridSetDigit(it) },
                onApplyFront = { viewModel.gridApplyFront() },
                onApplyBack = { viewModel.gridApplyBack() },
                onApplyDoor19 = { viewModel.gridApplyDoor19() },
                onOpenExpandedView = { viewModel.setGridExpanded(true) },
                onSideChange = { viewModel.updateGridOptions(sideSelection = it) },
                onAmountChange = { viewModel.updateGridOptions(amountPerNumberThbInput = it) },
                onAddToCart = { viewModel.submitGridSelection() }
            )
            2 -> PasteBatchOrderTabCard(
                state = state,
                onTextChange = { viewModel.updatePasteText(it) },
                onSubmitBatch = { viewModel.submitPasteBatch() }
            )
        }

        // 4. ตะกร้าในเครื่อง (Durable Cart: แก้/ลบ/ล้างพร้อมยืนยัน + แยก Distinct Numbers กับ Line Count)
        DurableCartSectionCard(
            state = state,
            onCustomerNoteChange = { viewModel.updateCustomerNote(it) },
            onRequestEditLine = { line ->
                lineToEdit = line.cartLineId
                editLineAmountInput = ExactMoneyMath.formatMinorToThbString(line.amountMinor).replace(",", "")
            },
            onDeleteLine = { viewModel.removeCartLine(it) },
            onRequestClearCart = { viewModel.requestClearCartConfirm(true) },
            onPrepareReview = { viewModel.prepareReviewQuote() }
        )

        // 5. เครื่องมือตรวจสอบ Operation เดิม (กรณีแอปดับกะทันหันหรือไม่แน่ใจว่าบิลก่อนหน้าบันทึกสำเร็จหรือไม่)
        OperationRecoveryCard(
            lastPendingOperationKey = state.lastPendingOperationKey,
            manualKeyInput = manualOperationKeyLookup,
            onManualKeyChange = { manualOperationKeyLookup = it },
            onVerifyKey = { keyToCheck -> viewModel.verifyPreviousOperation(keyToCheck) }
        )
    }

    // Dialog ขยายตาราง 00–99 (Expanded View สำหรับจอแคบ ตาม 05_UI)
    if (state.isGridExpandedForNarrowScreen) {
        ExpandedGrid10x10Dialog(
            selectedNumbers = state.gridSelectedNumbers,
            canUndo = state.gridUndoStack.isNotEmpty(),
            onToggleNumber = { viewModel.toggleGridNumber(it) },
            onSelectAll = { viewModel.gridSelectAll100() },
            onClear = { viewModel.gridClearSelection() },
            onUndo = { viewModel.gridUndo() },
            onReverseSelected = { viewModel.gridApplyReverse() },
            onDismiss = { viewModel.setGridExpanded(false) }
        )
    }

    // Dialog ยืนยันการล้างตะกร้าทั้งหมด
    if (state.showClearCartConfirmDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.requestClearCartConfirm(false) },
            title = { Text("ยืนยันการล้างตะกร้าในเครื่อง") },
            text = {
                Text(
                    "ต้องการล้างรายการทั้งหมด ${state.cartLineCount} รายการ (${state.cartDistinctNumberCount} เลขไม่ซ้ำ) ออกจากตะกร้าใช่หรือไม่?"
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmClearCart() }) {
                    Text("ยืนยันล้างตะกร้า")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.requestClearCartConfirm(false) }) {
                    Text("ยกเลิก")
                }
            }
        )
    }

    // Dialog แก้ไขจำนวนเงินรายบรรทัดในตะกร้า
    val editingLineId = lineToEdit
    if (editingLineId != null) {
        AlertDialog(
            onDismissRequest = { lineToEdit = null },
            title = { Text("แก้ไขจำนวนเงินในตะกร้า") },
            text = {
                OutlinedTextField(
                    value = editLineAmountInput,
                    onValueChange = { editLineAmountInput = it },
                    label = { Text("จำนวนเงินใหม่ (บาท)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.editCartLineAmount(editingLineId, editLineAmountInput)
                        lineToEdit = null
                    }
                ) {
                    Text("บันทึกยอดใหม่")
                }
            },
            dismissButton = {
                TextButton(onClick = { lineToEdit = null }) {
                    Text("ยกเลิก")
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualOrderTabCard(
    state: OrderEntryUiState,
    onNumberChange: (String) -> Unit,
    onSideChange: (OrderSideSelection) -> Unit,
    onIncludeReverseChange: (Boolean) -> Unit,
    onAmountChange: (String) -> Unit,
    onAddToCart: () -> Unit
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
                text = "แท็บ 1: กรอกเลขเอง (00–99)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = state.manualNumberInput,
                onValueChange = { if (it.length <= 2) onNumberChange(it) },
                label = { Text("เลข 2 หลัก (00–99)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text(text = "เลือกด้าน:", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrderSideSelection.entries.forEach { sideOption ->
                    FilterChip(
                        selected = state.manualSideSelection == sideOption,
                        onClick = { onSideChange(sideOption) },
                        label = { Text(sideOption.thaiLabel) }
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onIncludeReverseChange(!state.manualIncludeReverse) }
            ) {
                Checkbox(
                    checked = state.manualIncludeReverse,
                    onCheckedChange = onIncludeReverseChange
                )
                Text(
                    text = "กลับเลขด้วย (คงเลขเดิมไว้และเพิ่มเลขกลับแบบไม่ซ้ำ เช่น 31 -> 13, 31)",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            OutlinedTextField(
                value = state.manualAmountThbInput,
                onValueChange = onAmountChange,
                label = { Text("จำนวนเงินต่อเลข/ด้าน (บาท)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = onAddToCart,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("เพิ่มลงตะกร้าในเครื่อง")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Grid10x10OrderTabCard(
    state: OrderEntryUiState,
    onToggleNumber: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onUndo: () -> Unit,
    onReverseSelected: () -> Unit,
    onSelectDigit: (String) -> Unit,
    onApplyFront: () -> Unit,
    onApplyBack: () -> Unit,
    onApplyDoor19: () -> Unit,
    onOpenExpandedView: () -> Unit,
    onSideChange: (OrderSideSelection) -> Unit,
    onAmountChange: (String) -> Unit,
    onAddToCart: () -> Unit
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "แท็บ 2: ตาราง 00–99 (10×10) • เลือกแล้ว ${state.gridSelectedNumbers.size} เลข",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(onClick = onOpenExpandedView) {
                    Text("ขยายเต็มจอ (สำหรับจอแคบ)")
                }
            }

            // ปุ่มเครื่องมือจัดการตาราง: เลือกทั้งหมด / ล้าง / Undo / กลับเลข
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(onClick = onSelectAll) { Text("เลือกทั้งหมด (100)") }
                OutlinedButton(onClick = onClear) { Text("ล้างที่เลือก") }
                OutlinedButton(
                    onClick = onUndo,
                    enabled = state.gridUndoStack.isNotEmpty()
                ) {
                    Text("Undo ย้อนกลับ (${state.gridUndoStack.size})")
                }
                OutlinedButton(
                    onClick = onReverseSelected,
                    enabled = state.gridSelectedNumbers.isNotEmpty()
                ) {
                    Text("กลับเลขที่เลือก")
                }
            }

            // ตัวเลือกหลัก 0–9 สำหรับ รูดหน้า / รูดหลัง / 19 ประตู
            Text(
                text = "เลือกเลขหลัก (0–9) สำหรับ รูดหน้า / รูดหลัง / 19 ประตู (หลักที่เลือก: ${state.gridSelectedDigit}):",
                style = MaterialTheme.typography.labelLarge
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ('0'..'9').forEach { digitChar ->
                    val digitStr = digitChar.toString()
                    FilterChip(
                        selected = state.gridSelectedDigit == digitStr,
                        onClick = { onSelectDigit(digitStr) },
                        label = { Text(digitStr) }
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(onClick = onApplyFront) {
                    Text("รูดหน้า ${state.gridSelectedDigit} (+10 เลข)")
                }
                Button(onClick = onApplyBack) {
                    Text("รูดหลัง ${state.gridSelectedDigit} (+10 เลข)")
                }
                Button(onClick = onApplyDoor19) {
                    Text("19 ประตู ${state.gridSelectedDigit} (+19 เลข)")
                }
            }

            // ตาราง 00–99 ขนาด 10×10
            NumberGrid10x10Component(
                selectedNumbers = state.gridSelectedNumbers,
                onToggleNumber = onToggleNumber
            )

            HorizontalDivider()

            Text(text = "ด้านและจำนวนเงินต่อเลข:", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrderSideSelection.entries.forEach { option ->
                    FilterChip(
                        selected = state.gridSideSelection == option,
                        onClick = { onSideChange(option) },
                        label = { Text(option.thaiLabel) }
                    )
                }
            }

            OutlinedTextField(
                value = state.gridAmountPerNumberThbInput,
                onValueChange = onAmountChange,
                label = { Text("เงินต่อเลข (บาท)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            val totalLinesToCreate = state.gridSelectedNumbers.size * state.gridSideSelection.sides.size
            Text(
                text = "สรุปก่อนเพิ่มลงตะกร้า: เลือก ${state.gridSelectedNumbers.size} เลข × ${state.gridSideSelection.sides.size} ด้าน = $totalLinesToCreate รายการ",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )

            Button(
                onClick = onAddToCart,
                enabled = state.gridSelectedNumbers.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("เพิ่ม ${state.gridSelectedNumbers.size} เลข ($totalLinesToCreate รายการ) ลงตะกร้า")
            }
        }
    }
}

@Composable
private fun NumberGrid10x10Component(
    selectedNumbers: Set<String>,
    onToggleNumber: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (row in 0 until 10) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (col in 0 until 10) {
                    val numberStr = "%02d".format(row * 10 + col)
                    val isSelected = selectedNumbers.contains(numberStr)
                    val bgColor = if (isSelected) NavyPrimary else MaterialTheme.colorScheme.surfaceVariant
                    val textColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .background(bgColor, RoundedCornerShape(6.dp))
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) TealSecondary else MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(6.dp)
                            )
                            .clickable { onToggleNumber(numberStr) }
                            .semantics {
                                contentDescription = if (isSelected) {
                                    "เลข $numberStr เลือกอยู่ กดเพื่อยกเลิก"
                                } else {
                                    "เลข $numberStr ยังไม่ได้เลือก กดเพื่อเลือก"
                                }
                            }
                    ) {
                        Text(
                            text = numberStr,
                            color = textColor,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpandedGrid10x10Dialog(
    selectedNumbers: Set<String>,
    canUndo: Boolean,
    onToggleNumber: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onUndo: () -> Unit,
    onReverseSelected: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ตารางขยาย 00–99 (เลือกแล้ว ${selectedNumbers.size} เลข)",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Button(onClick = onDismiss) {
                        Text("เสร็จสิ้น / ย่อกลับ")
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onSelectAll) { Text("เลือกทั้งหมด") }
                    OutlinedButton(onClick = onClear) { Text("ล้าง") }
                    OutlinedButton(onClick = onUndo, enabled = canUndo) { Text("Undo") }
                    OutlinedButton(onClick = onReverseSelected, enabled = selectedNumbers.isNotEmpty()) {
                        Text("กลับเลข")
                    }
                }

                NumberGrid10x10Component(
                    selectedNumbers = selectedNumbers,
                    onToggleNumber = onToggleNumber
                )
            }
        }
    }
}

@Composable
private fun PasteBatchOrderTabCard(
    state: OrderEntryUiState,
    onTextChange: (String) -> Unit,
    onSubmitBatch: () -> Unit
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
                text = "แท็บ 3: วางข้อความหลายบรรทัด (ผิดแม้บรรทัดเดียวบล็อกทั้งชุด D03)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "รูปแบบต่อบรรทัด: <เลข 2 หลัก> <บน/ล่าง> <จำนวนเงินบาท> เช่น '31 บน 100'",
                style = MaterialTheme.typography.bodyMedium
            )

            OutlinedTextField(
                value = state.pasteRawTextInput,
                onValueChange = onTextChange,
                label = { Text("วางข้อความรายการที่นี่") },
                minLines = 5,
                modifier = Modifier.fillMaxWidth()
            )

            if (state.pasteErrors.isNotEmpty()) {
                Surface(
                    color = ErrorRedBg,
                    contentColor = ErrorRedText,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "ถูกบล็อกทั้งชุดเนื่องจากพบบรรทัดที่ไม่ถูกต้อง (${state.pasteErrors.size} บรรทัด):",
                            fontWeight = FontWeight.Bold
                        )
                        state.pasteErrors.forEach { err ->
                            Text(
                                text = "• บรรทัดที่ ${err.lineNumber} ('${err.rawLine}'): ${err.reasonTh}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            Button(
                onClick = onSubmitBatch,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("ตรวจสอบและนำเข้าตะกร้าทั้งชุด")
            }
        }
    }
}

@Composable
private fun DurableCartSectionCard(
    state: OrderEntryUiState,
    onCustomerNoteChange: (String) -> Unit,
    onRequestEditLine: (CartLineEntity) -> Unit,
    onDeleteLine: (Long) -> Unit,
    onRequestClearCart: () -> Unit,
    onPrepareReview: () -> Unit
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ตะกร้าในเครื่อง (Durable Cart)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (state.cartLines.isNotEmpty()) {
                    OutlinedButton(onClick = onRequestClearCart) {
                        Text("ล้างตะกร้าทั้งหมด")
                    }
                }
            }

            Surface(
                color = WarningAmberBg,
                contentColor = WarningAmberText,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "หมายเหตุสำคัญ (D04): ตะกร้าในเครื่องยังไม่ใช่การจองวงเงิน วงเงินจะถูกตัดเมื่อกดยืนยันบันทึกบิลในหน้าตรวจทานสำเร็จเท่านั้น",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(8.dp)
                )
            }

            // แยกแสดงจำนวนเลขไม่ซ้ำ (Distinct Numbers) กับจำนวนรายการ (Line Count) และยอดรวม ตาม 05_UI
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("จำนวนเลขไม่ซ้ำ (Distinct)", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = "${state.cartDistinctNumberCount} เลข",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column {
                        Text("จำนวนรายการ (Line Count)", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = "${state.cartLineCount} รายการ",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("ยอดซื้อรวมในตะกร้า", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = "${ExactMoneyMath.formatMinorToThbString(state.cartTotalAmountMinor)} บาท",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            OutlinedTextField(
                value = state.customerNoteTh,
                onValueChange = onCustomerNoteChange,
                label = { Text("หมายเหตุบิล / ชื่ออ้างอิงในเครื่อง (ถ้ามี)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (state.cartLines.isEmpty()) {
                Text(
                    text = "ยังไม่มีรายการในตะกร้า",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                state.cartLines.forEachIndexed { idx, line ->
                    val sideTh = if (line.side == BetSide.TOP.name) "บน" else "ล่าง"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${idx + 1}. เลข ${line.numberTwoDigit} ($sideTh) • ${ExactMoneyMath.formatMinorToThbString(line.amountMinor)} บาท [${line.entrySource}]",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onRequestEditLine(line) }) {
                                Text("แก้ยอด")
                            }
                            TextButton(onClick = { onDeleteLine(line.cartLineId) }) {
                                Text("ลบ")
                            }
                        }
                    }
                    HorizontalDivider()
                }

                Button(
                    onClick = onPrepareReview,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("ตรวจทานอัตราจ่ายและวงเงินก่อนยืนยัน (Review)")
                }
            }
        }
    }
}

@Composable
private fun ReviewBeforeLocalConfirmCard(
    review: ReviewQuoteUiModel,
    currentActivePolicyVersion: Long,
    hasPolicyChangedWarning: Boolean,
    onRecomputeQuote: () -> Unit,
    onCancelReview: () -> Unit,
    onConfirmLocalCommit: () -> Unit
) {
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
            Text(
                text = "หน้าตรวจทานก่อนยืนยันบันทึกในเครื่อง (Review & Local Confirm)",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "งวด: ${review.roundCode} • ผูกกับรุ่นนโยบาย #${review.boundPolicyVersion} • Operation Key: ${review.operationKey}",
                style = MaterialTheme.typography.bodyMedium
            )

            // กฎ 05_UI & D04: คำเตือน Policy Change หากรุ่นนโยบายเปลี่ยนก่อนกดยืนยัน
            if (hasPolicyChangedWarning) {
                Surface(
                    color = ErrorRedBg,
                    contentColor = ErrorRedText,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "คำเตือนนโยบายเปลี่ยนแปลง (Policy Change Warning): นโยบายของงวดเปลี่ยนจากรุ่น #${review.boundPolicyVersion} เป็นรุ่น #$currentActivePolicyVersion",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "ระบบไม่อนุญาตให้เปลี่ยนราคาเงียบๆ กรุณากด 'คำนวณใบตรวจทานใหม่ตามนโยบายล่าสุด' ก่อนยืนยัน",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(onClick = onRecomputeQuote) {
                            Text("คำนวณใบตรวจทานใหม่ตามนโยบายรุ่น #$currentActivePolicyVersion")
                        }
                    }
                }
            }

            review.quotedLines.forEach { qLine ->
                Text(
                    text = "• รายการ #${qLine.lineNumber}: เลข ${qLine.numberTwoDigit} (${qLine.side.thaiLabel}) ยอดซื้อ ${ExactMoneyMath.formatMinorToThbString(qLine.amountMinor)} บาท × อัตราจ่าย ${qLine.rateSnapshotExact} = ยอดจ่ายคาดการณ์ ${ExactMoneyMath.formatMinorToThbString(qLine.provisionalPayoutMinor)} บาท (${qLine.roundingMode})",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            HorizontalDivider()

            Text(
                text = "สรุปใบตรวจทาน: ${review.distinctNumberCount} เลขไม่ซ้ำ • ${review.lineCount} รายการ • ยอดซื้อรวม ${ExactMoneyMath.formatMinorToThbString(review.totalAmountMinor)} บาท (${review.totalAmountMinor} สตางค์)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCancelReview,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("กลับไปแก้ไขตะกร้า")
                }
                Button(
                    onClick = onConfirmLocalCommit,
                    enabled = !hasPolicyChangedWarning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("ยืนยันบันทึกบิลในเครื่อง (Local Confirm)")
                }
            }
        }
    }
}

@Composable
private fun CommittedBillSuccessCard(
    receipt: CommittedBillReceiptUiModel,
    onDismiss: () -> Unit
) {
    Surface(
        color = StatusOpenBg,
        contentColor = StatusOpenText,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, StatusOpenText, RoundedCornerShape(10.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = if (receipt.replayedFromDurableOperation) {
                    "ยืนยันสำเร็จ (กู้คืน/ตรวจซ้ำจาก Operation เดิมโดยไม่ตัดวงเงินซ้ำ)"
                } else {
                    "บันทึกบิลลงฐานข้อมูลในเครื่องสำเร็จ (Committed)"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text("รหัสบิล (Human Bill Code): ${receipt.humanBillCode}", fontWeight = FontWeight.Bold)
            Text("รหัสอ้างอิงฐานข้อมูล (Committed Bill UUID): ${receipt.committedBillId}")
            Text("รหัส Revision: ${receipt.committedRevisionId} (Revision #${receipt.revisionNumber})")
            Text("Operation Key: ${receipt.operationKey}")
            Text(
                "จำนวนเลขไม่ซ้ำ: ${receipt.distinctNumberCount} เลข • จำนวนรายการ: ${receipt.lineCount} รายการ • ยอดรวม: ${ExactMoneyMath.formatMinorToThbString(receipt.totalAmountMinor)} บาท"
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(onClick = onDismiss) {
                Text("รับทราบรหัสบิลและทำรายการต่อ")
            }
        }
    }
}

@Composable
private fun OperationRecoveryCard(
    lastPendingOperationKey: String?,
    manualKeyInput: String,
    onManualKeyChange: (String) -> Unit,
    onVerifyKey: (String) -> Unit
) {
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
                text = "ตรวจสอบ Operation เดิม (กรณีแอปถูกปิดกะทันหันหรือไม่แน่ใจผลการบันทึก)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (!lastPendingOperationKey.isNullOrBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Operation ล่าสุด: $lastPendingOperationKey",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(onClick = { onVerifyKey(lastPendingOperationKey) }) {
                        Text("ตรวจสถานะล่าสุด")
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = manualKeyInput,
                    onValueChange = onManualKeyChange,
                    label = { Text("ระบุ Operation Key ที่ต้องการตรวจสอบ") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { onVerifyKey(manualKeyInput) },
                    enabled = manualKeyInput.isNotBlank()
                ) {
                    Text("ตรวจสอบ")
                }
            }
        }
    }
}
