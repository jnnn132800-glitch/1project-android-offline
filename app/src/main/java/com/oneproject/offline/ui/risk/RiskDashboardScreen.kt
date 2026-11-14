package com.oneproject.offline.ui.risk

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
import com.oneproject.offline.domain.engine.RiskAlertStatus
import com.oneproject.offline.ui.ShellUiState
import com.oneproject.offline.ui.theme.DangerRedBg
import com.oneproject.offline.ui.theme.DangerRedText
import com.oneproject.offline.ui.theme.NavyPrimary
import com.oneproject.offline.ui.theme.StatusOpenBg
import com.oneproject.offline.ui.theme.StatusOpenText
import com.oneproject.offline.ui.theme.WarningAmberBg
import com.oneproject.offline.ui.theme.WarningAmberText

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RiskDashboardScreen(
    shellState: ShellUiState,
    viewModel: RiskDashboardViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val bundle = state.dashboardBundle

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "ภาพรวมและความเสี่ยงในเครื่อง (Phase 09 • 100×100 Valid Outcomes)",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        // แถบแสดงสถานะฐานข้อมูลและกฎ D06
        Surface(
            color = WarningAmberBg,
            contentColor = WarningAmberText,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, WarningAmberText.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .semantics {
                    contentDescription = "โหมดความเสี่ยง D06 คำนวณแยกตามผลลัพธ์ที่เป็นไปได้ 100 คูณ 100 กรณี ไม่ใช้ AI ทำนายผล"
                }
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "กติกาความเสี่ยง [DEMO DEFAULT — D06 ปริภูมิผลลัพธ์ 100×100 = 10,000 กรณี]",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "• คำนวณ net(outcome) = max(0, gross(outcome) - acceptedRecoverable(outcome)) แยกทีละผลลัพธ์แล้วจึงหาค่าสูงสุด • ห้ามนำค่าสูงสุดคนละเลขมาลบกัน • รายการตีออกสถานะ PENDING ลดความเสี่ยง = 0.00 บาท • ไม่ใช้ AI ทำนายผลรางวัล",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (state.isSnapshotStale || state.isDecisionBlocked) {
            Surface(
                color = DangerRedBg,
                contentColor = DangerRedText,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "บล็อกการตัดสินใจ [STALE_RISK_BLOCKED]: ข้อมูลความเสี่ยงล้าสมัยเนื่องจากมีการแก้ไขบิล/นโยบาย/คู่สัญญาหลังเวลาคำนวณล่าสุด กรุณากดปุ่ม 'คำนวณความเสี่ยงใหม่ (Refresh Risk)' ด้านล่าง",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(12.dp)
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
                    text = err,
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
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.refreshRiskDashboard() },
                colors = ButtonDefaults.buttonColors(containerColor = NavyPrimary),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("คำนวณความเสี่ยงใหม่ (Refresh As-Of Snapshot)")
            }

            OutlinedButton(
                onClick = { viewModel.checkStaleness() },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("ตรวจสอบความสดใหม่ของข้อมูล (Check Stale)")
            }
        }

        if (bundle != null) {
            val risk = bundle.riskSnapshot
            val cash = bundle.cashScenarios

            // 1. ข้อมูลความครบถ้วนและเวลา As-Of ในเครื่อง
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "1. สถานะข้อมูลในเครื่องและเวลาคำนวณ (Local Completeness & As-Of Basis)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    HorizontalDivider()
                    Text(
                        text = "งวด: ${bundle.roundCode} (${shellState.roundStatus.thaiLabel}) • นโยบายรุ่น #${risk.policyVersion}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "จำนวนบิล ACTIVE ที่นำมาคำนวณ: ${bundle.activeBillCount} บิล (${bundle.activeLineCount} บรรทัดเลข) • ปริภูมิผลลัพธ์ที่ประเมินครบ: ${risk.evaluatedValidOutcomeCount} กรณี (100×100)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "เวลาคำนวณความเสี่ยงในเครื่อง (Computed As-Of Epoch ms): ${risk.computedAsOfEpochMs} • เวลาแก้ไขข้อมูลล่าสุด: ${risk.latestDataMutationEpochMs} • สถานะ: ${if (risk.isStale) "ล้าสมัย [STALE]" else "เป็นปัจจุบัน [FRESH]"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "การแจ้งเตือนเพดานจ่ายสุทธิ: เกินเพดาน (>100%) = ${risk.capBreachCellCount} รายการ [CAP_BREACH] • ใกล้เต็มเพดาน (>=80%) = ${risk.nearCapWarningCellCount} รายการ [WARN_80_PCT]",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (risk.capBreachCellCount > 0) DangerRedText else StatusOpenText
                    )
                }
            }

            // 2. ความเสี่ยงสูงสุด Gross vs Net แยกตามผลลัพธ์ที่เป็นไปได้ (100 x 100)
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
                        text = "2. ความเสี่ยงจ่ายรางวัลสูงสุด Gross vs Net (คำนวณแยกตามผลลัพธ์ 100×100)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    HorizontalDivider()

                    Text(
                        text = "ก) ความเสี่ยงขั้นต้นสูงสุดก่อนหักตีออก (Gross Exposure Maxima):",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "  • เลขบน Gross สูงสุด: เลข ${risk.maxGrossTopCell.numberTwoDigit} = ${ExactMoneyMath.formatMinorToThbString(risk.maxGrossTopCell.grossPayoutMinor)} บาท (ยอดซื้อ ${ExactMoneyMath.formatMinorToThbString(risk.maxGrossTopCell.activeReceiveMinor)} บาท)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "  • เลขล่าง Gross สูงสุด: เลข ${risk.maxGrossBottomCell.numberTwoDigit} = ${ExactMoneyMath.formatMinorToThbString(risk.maxGrossBottomCell.grossPayoutMinor)} บาท (ยอดซื้อ ${ExactMoneyMath.formatMinorToThbString(risk.maxGrossBottomCell.activeReceiveMinor)} บาท)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "  • ผลลัพธ์รวม Gross สูงสุด (บน ${risk.worstGrossJointOutcome.winningTopTwoDigit} + ล่าง ${risk.worstGrossJointOutcome.winningBottomTwoDigit}): ${ExactMoneyMath.formatMinorToThbString(risk.worstGrossJointOutcome.jointGrossPayoutMinor)} บาท",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )

                    HorizontalDivider()

                    Text(
                        text = "ข) ความเสี่ยงสุทธิสูงสุดหลังหักยอดตีออกที่คู่สัญญายอมรับตรงผลลัพธ์ (Net Exposure Maxima):",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "  • เลขบน Net สูงสุด: เลข ${risk.maxNetTopCell.numberTwoDigit} = ${ExactMoneyMath.formatMinorToThbString(risk.maxNetTopCell.netPayoutMinor)} บาท (Gross ${ExactMoneyMath.formatMinorToThbString(risk.maxNetTopCell.grossPayoutMinor)} - รับคืนตรงเลข ${ExactMoneyMath.formatMinorToThbString(risk.maxNetTopCell.acceptedRecoverableMinor)})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "  • เลขล่าง Net สูงสุด: เลข ${risk.maxNetBottomCell.numberTwoDigit} = ${ExactMoneyMath.formatMinorToThbString(risk.maxNetBottomCell.netPayoutMinor)} บาท (Gross ${ExactMoneyMath.formatMinorToThbString(risk.maxNetBottomCell.grossPayoutMinor)} - รับคืนตรงเลข ${ExactMoneyMath.formatMinorToThbString(risk.maxNetBottomCell.acceptedRecoverableMinor)})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "  • ผลลัพธ์รวม Net สูงสุด (บน ${risk.worstNetJointOutcome.winningTopTwoDigit} + ล่าง ${risk.worstNetJointOutcome.winningBottomTwoDigit}): ${ExactMoneyMath.formatMinorToThbString(risk.worstNetJointOutcome.jointNetPayoutMinor)} บาท",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )

                    HorizontalDivider()
                    Text(
                        text = "• ยอดส่งตีออกสถานะรอดำเนินการ (Pending Submitted): ${ExactMoneyMath.formatMinorToThbString(risk.totalPendingSubmittedAmountMinor)} บาท → ผลลดความเสี่ยง = ${ExactMoneyMath.formatMinorToThbString(risk.pendingExposureReductionMinor)} บาท [PENDING_NO_REDUCTION]",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = WarningAmberText
                    )
                }
            }

            // 3. สถานการณ์กระแสเงินสด (Cash Scenarios + CASH01 Fixture)
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
                        text = "3. สถานการณ์กระแสเงินสดและความสามารถรองรับการจ่ายสูงสุด (Cash Scenarios)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "กฎ 04_SPEC: เงินสดปลายงวด = เงินสดต้นงวด + เงินสดรับจริงที่มีอยู่ - ภาระจ่ายถึงกำหนด • ห้ามนับซ้ำลูกหนี้ค่าบิลค้างเก็บ (${ExactMoneyMath.formatMinorToThbString(cash.excludedUncollectedReceivableMinor)} บาทถูกตัดออกจากการเป็นเงินสดพร้อมจ่าย) • สถานการณ์มาตรฐานไม่นับยอดรับคืนจากคู่สัญญาเป็นเงินสดในมือ",
                        style = MaterialTheme.typography.bodySmall
                    )

                    OutlinedTextField(
                        value = state.openingCashMajorInput,
                        onValueChange = { viewModel.updateCashInputs(openingCashMajorInput = it) },
                        label = { Text("เงินสดต้นงวด (Opening Cash — บาท)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = state.minimumRequiredCashMajorInput,
                        onValueChange = { viewModel.updateCashInputs(minimumRequiredCashMajorInput = it) },
                        label = { Text("เกณฑ์เงินสดขั้นต่ำที่ต้องดำรง (Minimum Required Cash — บาท)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = { viewModel.refreshRiskDashboard() },
                        colors = ButtonDefaults.buttonColors(containerColor = NavyPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Text("อัปเดตการจำลองสถานการณ์เงินสด")
                    }

                    HorizontalDivider()
                    val defPos = cash.defaultConservativeScenario
                    Text(
                        text = "ก) สถานการณ์มาตรฐาน (Default Conservative — ไม่พึ่งเงินรับคืนคู่สัญญา):",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "  Opening ${ExactMoneyMath.formatMinorToThbString(defPos.openingCashMinor)} + Inflow(เงินสดเก็บจริง) ${ExactMoneyMath.formatMinorToThbString(defPos.availableInflowsMinor)} - Outflow(จ่ายแล้ว + GrossMax) ${ExactMoneyMath.formatMinorToThbString(defPos.horizonDueOutflowsMinor)} = Closing ${ExactMoneyMath.formatMinorToThbString(defPos.closingCashMinor)} บาท",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "  เงินสดขาดมือเทียบเกณฑ์ขั้นต่ำ (Shortfall): ${ExactMoneyMath.formatMinorToThbString(defPos.shortfallMinor)} บาท [${if (defPos.hasShortfallAlert) "SHORTFALL_ALERT" else "CASH_OK"}]",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (defPos.hasShortfallAlert) DangerRedText else StatusOpenText
                    )

                    val refPos = cash.matchedRecoverableReferenceScenario
                    Text(
                        text = "ข) สถานการณ์อ้างอิงเมื่อหักสิทธิรับคืนตามสัญญา (Reference Only — สิทธิรับคืนไม่ใช่เงินสดรับประกัน):",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "  Closing ${ExactMoneyMath.formatMinorToThbString(refPos.closingCashMinor)} บาท • Shortfall ${ExactMoneyMath.formatMinorToThbString(refPos.shortfallMinor)} บาท",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )

                    state.cash01IndependentResult?.let { c01 ->
                        HorizontalDivider()
                        Text(
                            text = "ค) ผลการตรวจชุดทดสอบมาตรฐาน CASH01 (07_TEST_FIXTURES.json):",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "  opening=${c01.openingCashMinor} | inflow=${c01.availableInflowsMinor} | outflow=${c01.horizonDueOutflowsMinor} -> closing=${c01.closingCashMinor} | minimum=${c01.minimumRequiredCashMinor} -> shortfall=${c01.shortfallMinor} สตางค์",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // 4. ตารางเลขที่มีความเสี่ยงสูงสุด (Active Exposure Numbers)
            val activeCells = (risk.topCells + risk.bottomCells)
                .filter { it.grossPayoutMinor > 0L || it.acceptedRecoverableMinor > 0L || it.pendingSubmittedMinor > 0L }
                .sortedByDescending { it.netPayoutMinor }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "4. รายการเลขที่มีภาระความเสี่ยงในงวด (${activeCells.size} รายการที่มีการเคลื่อนไหว)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    HorizontalDivider()
                    if (activeCells.isEmpty()) {
                        Text(
                            text = "ยังไม่มีรายการบิลหรือรายการตีออกในงวดนี้",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        activeCells.forEach { cell ->
                            val statusColor = when (cell.alertStatus) {
                                RiskAlertStatus.CRITICAL_CAP_BREACH -> DangerRedText
                                RiskAlertStatus.WARNING_NEAR_CAP -> WarningAmberText
                                RiskAlertStatus.NORMAL -> StatusOpenText
                            }
                            Text(
                                text = "เลข ${cell.numberTwoDigit} (${cell.side.thaiLabel}) [${cell.alertStatus.codeText}]: Gross ${ExactMoneyMath.formatMinorToThbString(cell.grossPayoutMinor)} | รับคืน(Accepted) ${ExactMoneyMath.formatMinorToThbString(cell.acceptedRecoverableMinor)} | รอผล(Pending) ${ExactMoneyMath.formatMinorToThbString(cell.pendingSubmittedMinor)} | Net ${ExactMoneyMath.formatMinorToThbString(cell.netPayoutMinor)} / เพดานจ่าย ${ExactMoneyMath.formatMinorToThbString(cell.effectivePayoutCapMinor)} บาท",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = statusColor
                            )
                        }
                    }
                }
            }
        }
    }
}
