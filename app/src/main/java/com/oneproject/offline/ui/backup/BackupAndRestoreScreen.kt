package com.oneproject.offline.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneproject.offline.core.security.BackupDesignSpec
import com.oneproject.offline.data.backup.StagingRestorePreview
import com.oneproject.offline.ui.theme.DangerCrimsonBg
import com.oneproject.offline.ui.theme.DangerCrimsonText
import com.oneproject.offline.ui.theme.NavyPrimary
import com.oneproject.offline.ui.theme.StatusOpenBg
import com.oneproject.offline.ui.theme.StatusOpenText
import com.oneproject.offline.ui.theme.WarningAmberBg
import com.oneproject.offline.ui.theme.WarningAmberText

/**
 * หน้าจอการสำรองและกู้คืนข้อมูลแบบยืนยันความถูกต้อง (Phase 06B) + เมนูจัดการเครื่อง
 *
 * กฎ 01, 04_SPEC (5.7), 05_UI และ D10:
 * - ส่งออก/นำเข้าผ่าน Storage Access Framework (SAF Document Picker)
 * - ใช้รหัสผ่านกู้คืนแบบพกพา (Portable Recovery Passphrase >= 8 ตัวอักษร) + PBKDF2WithHmacSHA256 + AES-256-GCM (AAD)
 * - ไม่พึ่งพา Android Keystore ของเครื่องเก่า จึงกู้คืนข้ามเครื่องได้เมื่อเครื่องเดิมสูญหายหรือพัง
 * - กู้คืน 2 ขั้นตอนเสมอ: (1) ถอดรหัสและตรวจความสมบูรณ์ใน Staging -> (2) ผู้ใช้กดยืนยันการเขียนทับแบบ Replace-Only (ไม่ผสานข้อมูล)
 */
@Composable
fun BackupAndRestoreScreen(
    viewModel: BackupAndRestoreViewModel,
    roundCode: String,
    auditCount: Int
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val exportDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            val outputStream = context.contentResolver.openOutputStream(uri)
            if (outputStream != null) {
                viewModel.exportToStreamAsync(outputStream)
            }
        }
    }

    val importDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream != null) {
                viewModel.stageAndValidateFromStreamAsync(inputStream)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "เพิ่มเติม • สำรองและกู้คืนข้อมูลออฟไลน์ (Phase 06B)",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        // แบนเนอร์แจ้งเตือนหลักเกณฑ์ความปลอดภัยตาม D10
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
                    text = "มาตรฐานความปลอดภัยไฟล์สำรอง (.1pbackup • D10 Portable Passphrase)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "• ระบบบังคับทำ PRAGMA wal_checkpoint(TRUNCATE) ก่อนสร้าง Snapshot และเข้ารหัสด้วย PBKDF2-HMAC-SHA256 (210,000 รอบ) + AES-256-GCM พร้อมผูก Header AAD",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "• ไม่ผูกกุญแจกับ Android Keystore ของเครื่องเก่า เพื่อให้กู้คืนลงเครื่องใหม่ได้เมื่อเครื่องเดิมพังหรือสูญหาย (ห้ามลืมรหัสผ่านกู้คืน เพราะไม่มีเซิร์ฟเวอร์หรือ Backdoor สำหรับกู้รหัสผ่าน)",
                    style = MaterialTheme.typography.bodySmall
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
                    text = "สำเร็จ: ${state.statusBannerTh}",
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
                    text = "ปฏิเสธรายการ: ${state.errorBannerTh}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // การ์ดที่ 1: ส่งออกไฟล์สำรองข้อมูล (SAF Export)
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
                    text = "1. ส่งออกไฟล์สำรองข้อมูล (Export Authenticated Backup)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "รวมเฟรมใน SQLite WAL เข้าไฟล์หลักและเข้ารหัสเป็นไฟล์ .1pbackup ผ่านตัวเลือกไฟล์ของระบบ (SAF Document Picker)",
                    style = MaterialTheme.typography.bodySmall
                )

                OutlinedTextField(
                    value = state.exportPassphraseInput,
                    onValueChange = viewModel::updateExportPassphrase,
                    label = {
                        Text("รหัสผ่านสำหรับเข้ารหัสไฟล์สำรอง (อย่างน้อย ${BackupDesignSpec.MIN_PASSPHRASE_LENGTH_CHARS} ตัวอักษร)")
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = state.exportNoteInput,
                    onValueChange = viewModel::updateExportNote,
                    label = { Text("หมายเหตุการสำรองข้อมูล (บันทึกในประวัติเครื่อง)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        val suggestedName = "1project_${roundCode}_${System.currentTimeMillis()}.1pbackup"
                        exportDocumentLauncher.launch(suggestedName)
                    },
                    enabled = !state.isBusy &&
                        state.exportPassphraseInput.length >= BackupDesignSpec.MIN_PASSPHRASE_LENGTH_CHARS,
                    colors = ButtonDefaults.buttonColors(containerColor = NavyPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "ปุ่มเลือกตำแหน่งบันทึกไฟล์สำรองข้อมูล .1pbackup"
                        }
                ) {
                    Text("ส่งออกไฟล์สำรอง (.1pbackup) ผ่าน SAF Document Picker")
                }
            }
        }

        // การ์ดที่ 2: นำเข้าและตรวจสอบไฟล์สำรองในพื้นที่ Staging ก่อนเขียนทับแบบ Replace-Only
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
                    text = "2. กู้คืนข้อมูลผ่านพื้นที่ตรวจเช็ค Staging (Replace-Only Restore)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "ขั้นตอนที่ 1: ถอดรหัสและตรวจ AES-GCM Tag + SHA-256 + PRAGMA integrity_check ในไฟล์พัก (Staging) โดยยังไม่แตะฐานข้อมูลจริง",
                    style = MaterialTheme.typography.bodySmall
                )

                OutlinedTextField(
                    value = state.restorePassphraseInput,
                    onValueChange = viewModel::updateRestorePassphrase,
                    label = {
                        Text("รหัสผ่านกู้คืนข้อมูลของไฟล์ .1pbackup")
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        importDocumentLauncher.launch(arrayOf("*/*"))
                    },
                    enabled = !state.isBusy &&
                        state.restorePassphraseInput.length >= BackupDesignSpec.MIN_PASSPHRASE_LENGTH_CHARS,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "ปุ่มเลือกไฟล์สำรอง .1pbackup เพื่อตรวจสอบใน Staging"
                        }
                ) {
                    Text("เลือกไฟล์ .1pbackup และตรวจสอบความสมบูรณ์ใน Staging")
                }

                val stagedPreview = state.stagedPreview
                if (stagedPreview != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    StagedRestoreConfirmationSection(
                        preview = stagedPreview,
                        confirmationChecked = state.replaceOnlyConfirmationChecked,
                        isBusy = state.isBusy,
                        onCheckedChange = viewModel::setReplaceOnlyConfirmationChecked,
                        onConfirmRestore = viewModel::confirmReplaceOnlyRestoreAsync,
                        onCancelStaging = viewModel::cancelStagedRestore
                    )
                }
            }
        }

        // การ์ดที่ 3: ประวัติการสำรองและกู้คืนในเครื่อง (backup_metadata)
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
                    text = "3. ประวัติการสำรองและกู้คืนข้อมูลในเครื่อง (${state.backupHistory.size} รายการ)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (state.backupHistory.isEmpty()) {
                    Text(
                        text = "ยังไม่มีประวัติการส่งออกหรือกู้คืนไฟล์สำรองในงวดนี้",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    state.backupHistory.forEach { item ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = "${item.actionType} • รหัสไฟล์: ${item.archiveId}",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Schema v${item.schemaVersion} • WAL Checkpoint=${item.walCheckpointCompleted} • SHA-256=${item.payloadSha256.take(16)}...",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "หมายเหตุ: ${item.noteTh}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }

        // การ์ดที่ 4: เมนูย่อยสำหรับระบบที่จะเปิดใน Phase ถัดไป (Phase 08–11)
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
                    text = "4. สถานะโมดูลผู้ดูแลและบัญชีในเครื่อง (Audit Logs ปัจจุบัน: $auditCount รายการ)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "• จัดการงวดและปรับปรุงงวดที่ปิดแล้ว: เปิดใช้งานแล้วที่แท็บ 'บิล' -> แท็บย่อย 'จัดการงวด'",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "• สำรองและกู้คืนข้อมูล (WAL Checkpoint + AES-256-GCM + Staging Replace-Only): เปิดใช้งานแล้วในหน้านี้ (Phase 06B)",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "• บัญชีแยกประเภทคู่ (Balanced Double-Entry Ledger): เตรียมเปิดใช้งานใน Phase 08",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "• คู่สัญญาและการตีออกด้วยมือ (Pending ไม่ลดความเสี่ยง, ปิดโอนจริงภายนอก): เตรียมเปิดใช้งานใน Phase 10",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "• ตั้งค่าล็อกแอปในเครื่อง (Salted PBKDF2 PIN Hash, Clock/Storage Guard): เตรียมเปิดใช้งานใน Phase 11",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun StagedRestoreConfirmationSection(
    preview: StagingRestorePreview,
    confirmationChecked: Boolean,
    isBusy: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onConfirmRestore: () -> Unit,
    onCancelStaging: () -> Unit
) {
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
                text = "ผลการตรวจไฟล์ในพื้นที่ Staging ผ่านครบทุกข้อ (พร้อมสำหรับ Replace-Only)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "• รหัสไฟล์สำรอง: ${preview.header.archiveId} (Schema v${preview.header.roomSchemaVersion})",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "• ขนาดฐานข้อมูล: ${preview.header.payloadUnencryptedSizeBytes} ไบต์ • SHA-256: ${preview.header.payloadSha256Hex.take(20)}...",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "• PRAGMA integrity_check: ${preview.validationReport.integrityCheckResult} • Foreign Key Violations: ${preview.validationReport.foreignKeyViolationCount}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "• ข้อมูลภายในไฟล์สำรอง: งวดล่าสุด '${preview.validationReport.previewRoundCode}' • จำนวนบิล ${preview.validationReport.previewBillCount} บิล • ประวัติตรวจสอบ ${preview.validationReport.previewAuditCount} รายการ",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = confirmationChecked,
                    onCheckedChange = onCheckedChange
                )
                Text(
                    text = "ฉันยืนยันการกู้คืนแบบทับข้อมูลเดิมทั้งหมด (Replace-Only • ไม่มีการผสานหรือรวมบิลเข้าด้วยกัน และระบบจะสำรองไฟล์เดิมไว้ที่ .pre_restore_rollback)",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onCancelStaging,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("ยกเลิกและลบไฟล์ Staging")
                }

                Button(
                    onClick = onConfirmRestore,
                    enabled = !isBusy && confirmationChecked,
                    colors = ButtonDefaults.buttonColors(containerColor = DangerCrimsonText),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("ยืนยันกู้คืนทับข้อมูลเดิม (Replace-Only)")
                }
            }
        }
    }
}
