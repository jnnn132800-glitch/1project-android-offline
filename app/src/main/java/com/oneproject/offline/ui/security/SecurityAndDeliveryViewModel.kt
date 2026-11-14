package com.oneproject.offline.ui.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.oneproject.offline.core.security.DeviceGuardrailEvaluation
import com.oneproject.offline.core.security.LocalAppLockAndGuardrailsEngine
import com.oneproject.offline.core.security.LocalOperationalRole
import com.oneproject.offline.core.security.PinVerificationResult
import com.oneproject.offline.core.security.SaltedPinRecord
import com.oneproject.offline.data.repository.LedgerAndReconciliationRepository
import com.oneproject.offline.ui.navigation.OneProjectDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class DeliveryGateCheckItem(
    val decisionCode: String,
    val titleTh: String,
    val statusBadge: String,
    val isResolved: Boolean,
    val detailTh: String
)

data class SecurityAndDeliveryUiState(
    val activeRole: LocalOperationalRole = LocalOperationalRole.OPERATOR,
    val isAppLocked: Boolean = false,
    val configuredPinRecord: SaltedPinRecord? = null,
    val pinInput: String = "",
    val selectedRoleForSetup: LocalOperationalRole = LocalOperationalRole.SUPERVISOR,
    val guardrailEvaluation: DeviceGuardrailEvaluation = LocalAppLockAndGuardrailsEngine.evaluateDeviceHealth(
        currentClockEpochMs = 1_710_100_000_000L,
        latestRecordedEpochMs = 1_710_000_000_000L,
        availableStorageBytes = 512L * 1024L * 1024L
    ),
    val sampleRawLogInput: String = "Audit: user entered pin=123456 and passphrase=MySecretBackupPass2026 on local device",
    val scrubbedLogPreview: String = LocalAppLockAndGuardrailsEngine.scrubSensitiveText(
        "Audit: user entered pin=123456 and passphrase=MySecretBackupPass2026 on local device"
    ),
    val accessibilityDestinationsVerifiedCount: Int = OneProjectDestination.entries.count {
        it.titleTh.isNotBlank() && it.talkBackDescription.isNotBlank()
    },
    val totalDestinationsCount: Int = OneProjectDestination.entries.size,
    val singleDeviceSecurityDisclaimerTh: String = LocalAppLockAndGuardrailsEngine.SINGLE_DEVICE_SECURITY_DISCLAIMER_TH,
    val ownerKeystoreConfigPresent: Boolean = false,
    val deliveryGateItems: List<DeliveryGateCheckItem> = defaultDeliveryGates(),
    val statusBannerTh: String? = null,
    val errorBannerTh: String? = null
)

private fun defaultDeliveryGates(): List<DeliveryGateCheckItem> = listOf(
    DeliveryGateCheckItem(
        decisionCode = "D05",
        titleTh = "เพดานความเสี่ยง (Cap) ในตัวอย่าง 10,000 บาท vs 1,000 บาท",
        statusBadge = "DRAFT_BLOCKER",
        isResolved = false,
        detailTh = "รอเจ้าของงานยืนยันขั้นสุดท้ายสำหรับตารางนโยบายใช้งานจริง (การคำนวณใช้ค่าจาก PolicyVersion ที่อนุมัติในฐานข้อมูล)"
    ),
    DeliveryGateCheckItem(
        decisionCode = "D08",
        titleTh = "รหัสบัญชี (Account Code) และกฎการจัดสรรลงผังบัญชีจริง",
        statusBadge = "DRAFT_BLOCKER",
        isResolved = false,
        detailTh = "รองรับสมการบัญชีคู่ (Balanced Double-Entry) และแยก AccountScope ครบถ้วน แต่รหัสบัญชีจริงยังเป็น DEMO"
    ),
    DeliveryGateCheckItem(
        decisionCode = "D09",
        titleTh = "การสั่งโอนเงินจริงและเชื่อมต่อระบบชำระเงินภายนอก",
        statusBadge = "DISABLED_BY_DESIGN",
        isResolved = true,
        detailTh = "ปิดกั้นการโอนเงินจริงภายนอก 100% ตามข้อกำหนด บันทึกเฉพาะ Manual Transfer Batch ในเครื่อง"
    ),
    DeliveryGateCheckItem(
        decisionCode = "D10",
        titleTh = "กุญแจเข้ารหัสฐานข้อมูลขณะพักข้อมูล (Database-at-Rest Encryption)",
        statusBadge = "DRAFT_BLOCKER",
        isResolved = false,
        detailTh = "Room ไม่ได้เข้ารหัสไฟล์ .db อัตโนมัติ (ไฟล์สำรอง .1pbackup เข้ารหัส PBKDF2+AES-GCM แล้ว)"
    ),
    DeliveryGateCheckItem(
        decisionCode = "D11",
        titleTh = "การตรวจทานด้านกฎหมาย คอมพลายแอนซ์ และภาษี",
        statusBadge = "DRAFT_BLOCKER",
        isResolved = false,
        detailTh = "ห้ามใช้งานจริงเชิงพาณิชย์จนกว่าจะได้รับการลงนามอนุมัติด้านกฎหมายและบัญชีครบถ้วน"
    )
)

class SecurityAndDeliveryViewModel(
    private val ledgerRepository: LedgerAndReconciliationRepository,
    private val storageDirectoryProvider: () -> File? = { null },
    private val clockEpochMsProvider: () -> Long = { System.currentTimeMillis() }
) : ViewModel() {

    private val _uiState = MutableStateFlow(SecurityAndDeliveryUiState())
    val uiState: StateFlow<SecurityAndDeliveryUiState> = _uiState.asStateFlow()

    init {
        refreshGuardrailsAndStatus()
    }

    fun refreshGuardrailsAndStatus(
        overrideClockEpochMs: Long? = null,
        overrideAvailableStorageBytes: Long? = null
    ) {
        val currentClock = overrideClockEpochMs ?: clockEpochMsProvider()
        val latestMutation = ledgerRepository.computeLatestRiskMutationEpochMs(roundId = null)
        val availableBytes = overrideAvailableStorageBytes
            ?: storageDirectoryProvider()?.usableSpace
            ?: (512L * 1024L * 1024L)

        val evaluation = LocalAppLockAndGuardrailsEngine.evaluateDeviceHealth(
            currentClockEpochMs = currentClock,
            latestRecordedEpochMs = latestMutation,
            availableStorageBytes = availableBytes
        )

        _uiState.value = _uiState.value.copy(
            guardrailEvaluation = evaluation
        )
    }

    fun updatePinInput(value: String) {
        val digitsOnly = value.filter { it.isDigit() }.take(16)
        _uiState.value = _uiState.value.copy(
            pinInput = digitsOnly,
            errorBannerTh = null
        )
    }

    fun selectRoleForSetup(role: LocalOperationalRole) {
        _uiState.value = _uiState.value.copy(
            selectedRoleForSetup = role,
            errorBannerTh = null
        )
    }

    fun configureLocalPin(nowEpochMs: Long = clockEpochMsProvider()) {
        val pin = _uiState.value.pinInput
        try {
            val record = LocalAppLockAndGuardrailsEngine.createSaltedPinRecord(
                rawPin = pin,
                role = _uiState.value.selectedRoleForSetup,
                nowEpochMs = nowEpochMs
            )
            _uiState.value = _uiState.value.copy(
                configuredPinRecord = record,
                activeRole = record.role,
                isAppLocked = false,
                pinInput = "",
                statusBannerTh = "ตั้งค่า PIN (PBKDF2-HMAC-SHA256 ${record.iterations} รอบ พร้อม Salt) สำหรับบทบาท ${record.role.labelTh} สำเร็จ (ไม่เก็บ PIN เป็นข้อความธรรมดา)",
                errorBannerTh = null
            )
        } catch (e: IllegalArgumentException) {
            _uiState.value = _uiState.value.copy(
                errorBannerTh = e.message ?: "ตั้งค่า PIN ไม่สำเร็จ",
                statusBannerTh = null
            )
        }
    }

    fun lockAppSession() {
        if (_uiState.value.configuredPinRecord == null) {
            _uiState.value = _uiState.value.copy(
                errorBannerTh = "กรุณาตั้งค่ารหัส PIN ก่อนทดสอบล็อกหน้าจอแอป"
            )
            return
        }
        _uiState.value = _uiState.value.copy(
            isAppLocked = true,
            pinInput = "",
            statusBannerTh = "ล็อกหน้าจอแอปแล้ว กรุณาใส่รหัส PIN เพื่อปลดล็อก",
            errorBannerTh = null
        )
    }

    fun unlockWithPin(nowEpochMs: Long = clockEpochMsProvider()) {
        val record = _uiState.value.configuredPinRecord
        if (record == null) {
            _uiState.value = _uiState.value.copy(
                errorBannerTh = "ยังไม่ได้ตั้งค่ารหัส PIN ในเครื่อง"
            )
            return
        }
        when (val result = LocalAppLockAndGuardrailsEngine.verifyPin(
            candidatePin = _uiState.value.pinInput,
            record = record,
            nowEpochMs = nowEpochMs
        )) {
            is PinVerificationResult.Verified -> {
                _uiState.value = _uiState.value.copy(
                    configuredPinRecord = result.updatedRecord,
                    activeRole = result.role,
                    isAppLocked = false,
                    pinInput = "",
                    statusBannerTh = "ปลดล็อกสำเร็จ (บทบาทปัจจุบัน: ${result.role.labelTh})",
                    errorBannerTh = null
                )
            }
            is PinVerificationResult.InvalidPin -> {
                _uiState.value = _uiState.value.copy(
                    configuredPinRecord = result.updatedRecord,
                    pinInput = "",
                    errorBannerTh = result.reasonTh,
                    statusBannerTh = null
                )
            }
            is PinVerificationResult.LockedOut -> {
                _uiState.value = _uiState.value.copy(
                    configuredPinRecord = result.updatedRecord,
                    pinInput = "",
                    errorBannerTh = result.reasonTh,
                    statusBannerTh = null
                )
            }
        }
    }

    fun updateSampleRawLogInput(rawLog: String) {
        _uiState.value = _uiState.value.copy(
            sampleRawLogInput = rawLog,
            scrubbedLogPreview = LocalAppLockAndGuardrailsEngine.scrubSensitiveText(rawLog)
        )
    }

    fun simulateClockRollbackGuard() {
        val latest = ledgerRepository.computeLatestRiskMutationEpochMs(roundId = null)
            .coerceAtLeast(1_710_000_000_000L)
        refreshGuardrailsAndStatus(
            overrideClockEpochMs = latest - 300_000L, // 5 minutes backward
            overrideAvailableStorageBytes = 512L * 1024L * 1024L
        )
    }

    fun simulateLowStorageGuard() {
        val latest = ledgerRepository.computeLatestRiskMutationEpochMs(roundId = null)
            .coerceAtLeast(1_710_000_000_000L)
        refreshGuardrailsAndStatus(
            overrideClockEpochMs = latest + 5_000L,
            overrideAvailableStorageBytes = 8L * 1024L * 1024L // 8 MB (< 32 MB threshold)
        )
    }

    fun restoreHealthyDeviceGuard() {
        val latest = ledgerRepository.computeLatestRiskMutationEpochMs(roundId = null)
            .coerceAtLeast(1_710_000_000_000L)
        refreshGuardrailsAndStatus(
            overrideClockEpochMs = latest + 10_000L,
            overrideAvailableStorageBytes = 512L * 1024L * 1024L
        )
    }

    companion object {
        fun factory(
            ledgerRepository: LedgerAndReconciliationRepository,
            storageDirectoryProvider: () -> File? = { null }
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SecurityAndDeliveryViewModel(
                    ledgerRepository = ledgerRepository,
                    storageDirectoryProvider = storageDirectoryProvider
                ) as T
            }
        }
    }
}
