package com.oneproject.offline.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oneproject.offline.core.security.DeviceGuardrailStatus
import com.oneproject.offline.core.security.LocalOperationalRole

@Composable
fun SecurityAndDeliveryScreen(
    viewModel: SecurityAndDeliveryViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "ความปลอดภัยในเครื่อง ความทนทาน และการส่งมอบ (Phase 11 & 12)",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        // Single-device explicit security disclaimer card
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = state.singleDeviceSecurityDisclaimerTh
                    }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "ขอบเขตความปลอดภัยบนอุปกรณ์ออฟไลน์เครื่องเดียว",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = state.singleDeviceSecurityDisclaimerTh,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        state.statusBannerTh?.let { msg ->
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        state.errorBannerTh?.let { err ->
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = err,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 1. Device Guardrails: Monotonic Clock Rollback (D12) & Minimum Storage Guard
        item {
            val guardrail = state.guardrailEvaluation
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (guardrail.canCommitAuthoritativeMutations) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "ระบบเฝ้าระวังเวลาเครื่องย้อนกลับ (D12) และพื้นที่จัดเก็บขั้นต่ำ",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "สถานะ: ${guardrail.status.name} | อนุญาตบันทึกธุรกรรม: ${if (guardrail.canCommitAuthoritativeMutations) "อนุญาต (ALLOW)" else "ระงับ (BLOCKED)"}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = guardrail.bannerMessageTh,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.simulateClockRollbackGuard() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("จำลองเวลาย้อนกลับ")
                        }
                        OutlinedButton(
                            onClick = { viewModel.simulateLowStorageGuard() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("จำลองพื้นที่ใกล้เต็ม")
                        }
                        Button(
                            onClick = { viewModel.restoreHealthyDeviceGuard() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("คืนค่าปกติ")
                        }
                    }
                }
            }
        }

        // 2. Local App Lock (PBKDF2-HMAC-SHA256)
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "ระบบล็อกหน้าจอแอปและบทบาทในเครื่อง (Salted PBKDF2-HMAC-SHA256)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "บทบาทปัจจุบัน: ${state.activeRole.labelTh} | สถานะหน้าจอ: ${if (state.isAppLocked) "ล็อกอยู่ (LOCKED)" else "ปลดล็อกแล้ว (UNLOCKED)"}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    state.configuredPinRecord?.let { rec ->
                        Text(
                            text = "Hash Algorithm: ${rec.algorithm} (${rec.iterations} รอบ) | Salt(Base64): ${rec.saltBase64.take(12)}... | ความพยายามผิดสะสม: ${rec.failedAttempts}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LocalOperationalRole.entries.forEach { role ->
                            OutlinedButton(
                                onClick = { viewModel.selectRoleForSetup(role) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = if (state.selectedRoleForSetup == role) "✓ ${role.name}" else role.name,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = state.pinInput,
                        onValueChange = { viewModel.updatePinInput(it) },
                        label = { Text("รหัส PIN (ตัวเลข 6-16 หลัก ไม่เก็บเป็น Plaintext)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.configureLocalPin() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("ตั้งค่า PIN ใหม่")
                        }
                        OutlinedButton(
                            onClick = { viewModel.lockAppSession() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("ล็อกแอป")
                        }
                        Button(
                            onClick = { viewModel.unlockWithPin() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("ปลดล็อกด้วย PIN")
                        }
                    }
                }
            }
        }

        // 3. Privacy Log Scrubber & Accessibility Verification
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "ระบบกรองข้อมูลความลับออกจาก Log (Privacy Scrubber) และการเข้าถึง (TalkBack)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "ความครบถ้วนของคำอธิบาย TalkBack ในแถบนำทางหลัก: ${state.accessibilityDestinationsVerifiedCount} / ${state.totalDestinationsCount} หมวดหมู่",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    OutlinedTextField(
                        value = state.sampleRawLogInput,
                        onValueChange = { viewModel.updateSampleRawLogInput(it) },
                        label = { Text("ข้อความ Log ต้นฉบับ (ทดสอบการปิดบัง PIN/Passphrase)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "ผลลัพธ์หลังผ่าน PrivacyLogScrubber:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = state.scrubbedLogPreview,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // 4. Phase 12 Release Readiness & Decision Log Blockers
        item {
            Text(
                text = "สถานะการส่งมอบ Release (Phase 12) และรายการรออนุมัติก่อนใช้งานจริง",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        items(state.deliveryGateItems, key = { it.decisionCode }) { gate ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (gate.isResolved) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${gate.decisionCode}: ${gate.titleTh}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = gate.statusBadge,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    HorizontalDivider()
                    Text(
                        text = gate.detailTh,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
