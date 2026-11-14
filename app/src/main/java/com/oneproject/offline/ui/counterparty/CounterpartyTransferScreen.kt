package com.oneproject.offline.ui.counterparty

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
import com.oneproject.offline.data.local.entity.BetSide
import com.oneproject.offline.data.local.entity.TransferResponseStatus
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
fun CounterpartyTransferScreen(
    viewModel: CounterpartyTransferViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snap = state.roundSnapshot

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "คู่สัญญาและใบตีออกด้วยมือ (Phase 10 • Manual Transfer & Settlement Separation)",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        // แถบแสดงกฎ D09 & 04_SPEC: ปิดการส่งออก/โอนเงินภายนอกจริง และ Pending ลดความเสี่ยง = 0
        Surface(
            color = WarningAmberBg,
            contentColor = WarningAmberText,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, WarningAmberText.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .semantics {
                    contentDescription = "ระบบปิดการสั่งโอนเงินหรือส่งโพยภายนอกอัตโนมัติทั้งหมด รายการสถานะรอผลตอบรับไม่ลดความเสี่ยง"
                }
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "สถานะการเชื่อมต่อภายนอก [EXTERNAL_EXECUTION_DISABLED — D09 บันทึกด้วยมือในเครื่องเท่านั้น]",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "• ใบตีออกที่สร้างใหม่มีสถานะ PENDING และลดความเสี่ยงได้ 0.00 บาท • เฉพาะรายการที่บันทึกผลตอบรับ ACCEPTED หรือยอดที่รับจริงใน PARTIAL เท่านั้นที่นำไปหักความเสี่ยงของเลขผลลัพธ์ที่ตรงกัน • แยกบัญชีตั้งหนี้คู่สัญญา (COUNTERPARTY_SETTLEMENT) ออกจากความเสี่ยง",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        state.errorBannerTh?.let { err ->
            Surface(
                color = DangerRedBg,
                contentColor = DangerRedText,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "ปฏิเสธรายการ: $err",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        state.statusBannerTh?.let { ok ->
            Surface(
                color = StatusOpenBg,
                contentColor = StatusOpenText,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = ok,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // 1. สร้างใบตีออกด้วยมือ (สถานะ PENDING)
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
                    text = "1. สร้างใบตีออกด้วยมือในสถานะรอผลตอบรับ (Create PENDING Transfer Batch)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                OutlinedTextField(
                    value = state.draftNumberInput,
                    onValueChange = { viewModel.updateDraftLineInputs(numberInput = it) },
                    label = { Text("เลข 2 ตัวที่ต้องการส่งตีออก (00–99)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BetSide.entries.forEach { side ->
                        FilterChip(
                            selected = state.draftSide == side,
                            onClick = { viewModel.updateDraftLineInputs(side = side) },
                            label = { Text("ฝั่ง${side.thaiLabel} (${side.name})") },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = state.draftAmountMajorInput,
                    onValueChange = { viewModel.updateDraftLineInputs(amountMajorInput = it) },
                    label = { Text("ยอดเงินต้นที่ส่งตีออก (บาท เช่น 50.00)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.addDraftLineToStage() },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("เพิ่มบรรทัดลงร่างใบตีออก (${state.stagedDraftLines.size})")
                    }
                    if (state.stagedDraftLines.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { viewModel.clearStagedDraftLines() },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("ล้างรายการร่าง")
                        }
                    }
                }

                if (state.stagedDraftLines.isNotEmpty()) {
                    state.stagedDraftLines.forEach { d ->
                        Text(
                            text = "  • เลข ${d.numberTwoDigit} (${d.side.thaiLabel}): ${ExactMoneyMath.formatMinorToThbString(d.submittedAmountMinor)} บาท",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Button(
                    onClick = { viewModel.submitManualTransferBatchPending() },
                    colors = ButtonDefaults.buttonColors(containerColor = NavyPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text("บันทึกใบตีออกสถานะ PENDING (ลดความเสี่ยง = 0.00 บาท)")
                }
            }
        }

        // 2. บันทึกผลตอบรับจากคู่สัญญาด้วยมือ (ACCEPTED / PARTIAL / REJECTED)
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
                    text = "2. บันทึกผลตอบรับจากคู่สัญญาด้วยมือ (ACCEPTED / PARTIAL / REJECTED)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                val pendingBatches = snap?.batches?.filter { it.status == TransferResponseStatus.PENDING.name }.orEmpty()
                if (pendingBatches.isEmpty()) {
                    Text(
                        text = "ไม่มีใบตีออกที่ค้างสถานะ PENDING ในขณะนี้",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        text = "เลือกใบตีออกสถานะ PENDING:",
                        style = MaterialTheme.typography.labelLarge
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pendingBatches.forEach { b ->
                            FilterChip(
                                selected = state.selectedBatchIdForResponse == b.batchId,
                                onClick = { viewModel.updateResponseDraft(batchId = b.batchId) },
                                label = {
                                    Text("${b.batchId} • ยอดส่ง ${ExactMoneyMath.formatMinorToThbString(b.totalSubmittedMinor)} บาท")
                                },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }

                    Text(
                        text = "เลือกสถานะผลตอบรับจากคู่สัญญา:",
                        style = MaterialTheme.typography.labelLarge
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            TransferResponseStatus.ACCEPTED,
                            TransferResponseStatus.PARTIAL,
                            TransferResponseStatus.REJECTED
                        ).forEach { st ->
                            FilterChip(
                                selected = state.selectedResponseStatus == st,
                                onClick = { viewModel.updateResponseDraft(responseStatus = st) },
                                label = { Text("${st.name} (${st.thaiLabel})") },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }

                    if (state.selectedResponseStatus == TransferResponseStatus.PARTIAL) {
                        OutlinedTextField(
                            value = state.partialAcceptedMajorInput,
                            onValueChange = { viewModel.updateResponseDraft(partialAcceptedMajorInput = it) },
                            label = { Text("ยอดเงินต้นที่คู่สัญญารับจริงต่อรายการ (บาท)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    OutlinedTextField(
                        value = state.responseReferenceNoteThInput,
                        onValueChange = { viewModel.updateResponseDraft(referenceNoteThInput = it) },
                        label = { Text("หมายเลขอ้างอิง/หลักฐานการตอบรับจากคู่สัญญา (บังคับกรอก)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = { viewModel.recordManualTransferResponse() },
                        colors = ButtonDefaults.buttonColors(containerColor = TealSecondary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Text("ยืนยันบันทึกผลตอบรับคู่สัญญาและแยกตั้งหนี้ COUNTERPARTY_SETTLEMENT")
                    }
                }
            }
        }

        // 3. สรุปประวัติใบตีออกและรายการบัญชีคู่สัญญาแยกจากความเสี่ยง
        if (snap != null) {
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
                        text = "3. สรุปสถานะใบตีออกและบัญชีตั้งหนี้คู่สัญญา (Counterparty Summary)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    HorizontalDivider()
                    Text(
                        text = "• ยอดส่งตีออกสถานะ PENDING รวม: ${ExactMoneyMath.formatMinorToThbString(snap.totalPendingSubmittedMinor)} บาท (ลดความเสี่ยง = ${ExactMoneyMath.formatMinorToThbString(snap.pendingExposureReductionMinor)} บาท)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "• ยอดเงินต้นที่คู่สัญญายอมรับแล้ว (Accepted/Partial Principal): ${ExactMoneyMath.formatMinorToThbString(snap.totalAcceptedPrincipalMinor)} บาท",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "• เพดานสิทธิรับคืนตามสัญญาของรายการที่ยอมรับแล้ว: ${ExactMoneyMath.formatMinorToThbString(snap.totalAcceptedRecoverableCeilingMinor)} บาท (หักความเสี่ยงเฉพาะเมื่อผลรางวัลตรงกับเลขนั้น)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    HorizontalDivider()
                    snap.batches.forEach { b ->
                        Text(
                            text = "${b.batchId} [${b.status}] • ส่ง ${ExactMoneyMath.formatMinorToThbString(b.totalSubmittedMinor)} | รับจริง ${ExactMoneyMath.formatMinorToThbString(b.totalAcceptedMinor)} | สิทธิรับคืนตามสัญญา ${ExactMoneyMath.formatMinorToThbString(b.totalAcceptedRecoverableCeilingMinor)} บาท",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        b.lines.forEach { line ->
                            Text(
                                text = "   - เลข ${line.numberTwoDigit} (${line.side}) [${line.lineStatus}]: ส่ง ${ExactMoneyMath.formatMinorToThbString(line.submittedAmountMinor)} -> รับ ${ExactMoneyMath.formatMinorToThbString(line.acceptedAmountMinor)} @ อัตรา ${line.contractRateExact} (รับคืนเมื่อถูก = ${ExactMoneyMath.formatMinorToThbString(line.acceptedRecoverablePayoutMinor)} บาท)",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}
