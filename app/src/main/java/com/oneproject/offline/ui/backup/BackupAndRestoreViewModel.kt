package com.oneproject.offline.ui.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.oneproject.offline.core.security.BackupDesignSpec
import com.oneproject.offline.data.backup.BackupExportResult
import com.oneproject.offline.data.backup.OfflineBackupAndRestoreManager
import com.oneproject.offline.data.backup.RestoreExecutionResult
import com.oneproject.offline.data.backup.StagingPreviewResult
import com.oneproject.offline.data.backup.StagingRestorePreview
import com.oneproject.offline.data.local.entity.BackupMetadataEntity
import com.oneproject.offline.data.repository.OfflineBootstrapRepository
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BackupAndRestoreUiState(
    val exportPassphraseInput: String = "",
    val exportNoteInput: String = "สำรองข้อมูลประจำงวด (WAL Checkpoint + AES-256-GCM)",
    val restorePassphraseInput: String = "",
    val stagedPreview: StagingRestorePreview? = null,
    val replaceOnlyConfirmationChecked: Boolean = false,
    val isBusy: Boolean = false,
    val statusBannerTh: String? = null,
    val errorBannerTh: String? = null,
    val backupHistory: List<BackupMetadataEntity> = emptyList()
)

/**
 * Pure Kotlin Controller สำหรับจัดการสถานะการสำรองและกู้คืนข้อมูล (Phase 06B)
 * แยกจาก Android Looper เพื่อให้ทดสอบใน JUnit ได้อย่างแม่นยำ 100%
 */
class BackupAndRestoreWorkflowController(
    private val backupManager: OfflineBackupAndRestoreManager,
    private val bootstrapRepository: OfflineBootstrapRepository,
    private val scope: CoroutineScope,
    private val clockEpochMsProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val _uiState = MutableStateFlow(BackupAndRestoreUiState())
    val uiState: StateFlow<BackupAndRestoreUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
            bootstrapRepository.observeBackupHistory().collect { history ->
                _uiState.update { it.copy(backupHistory = history) }
            }
        }
    }

    fun updateExportPassphrase(value: String) {
        _uiState.update {
            it.copy(
                exportPassphraseInput = value,
                errorBannerTh = null
            )
        }
    }

    fun updateExportNote(value: String) {
        _uiState.update {
            it.copy(
                exportNoteInput = value,
                errorBannerTh = null
            )
        }
    }

    fun updateRestorePassphrase(value: String) {
        _uiState.update {
            it.copy(
                restorePassphraseInput = value,
                errorBannerTh = null
            )
        }
    }

    fun setReplaceOnlyConfirmationChecked(checked: Boolean) {
        _uiState.update {
            it.copy(
                replaceOnlyConfirmationChecked = checked,
                errorBannerTh = null
            )
        }
    }

    fun clearBanners() {
        _uiState.update {
            it.copy(
                statusBannerTh = null,
                errorBannerTh = null
            )
        }
    }

    /**
     * ส่งออกไฟล์สำรองข้อมูลแบบยืนยันความถูกต้อง (.1pbackup) ไปยัง OutputStream จาก SAF Document Picker
     */
    suspend fun executeExportToStream(outputStream: OutputStream): BackupExportResult {
        val current = _uiState.value
        val passChars = current.exportPassphraseInput.toCharArray()
        if (passChars.size < BackupDesignSpec.MIN_PASSPHRASE_LENGTH_CHARS) {
            passChars.fill('\u0000')
            val msg = "รหัสผ่านสำรองข้อมูลต้องมีความยาวอย่างน้อย ${BackupDesignSpec.MIN_PASSPHRASE_LENGTH_CHARS} ตัวอักษร"
            _uiState.update { it.copy(errorBannerTh = msg, statusBannerTh = null) }
            return BackupExportResult.Rejected(
                errorCode = com.oneproject.offline.core.security.BackupRestoreErrorCode.PASSPHRASE_TOO_SHORT,
                messageTh = msg
            )
        }

        _uiState.update { it.copy(isBusy = true, errorBannerTh = null, statusBannerTh = null) }
        val result = backupManager.exportConsistentAuthenticatedBackup(
            passphrase = passChars,
            outputStream = outputStream,
            nowEpochMs = clockEpochMsProvider(),
            noteTh = current.exportNoteInput
        )

        val refreshedHistory = try {
            bootstrapRepository.getBackupHistory()
        } catch (_: Exception) {
            _uiState.value.backupHistory
        }

        when (result) {
            is BackupExportResult.Success -> {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        exportPassphraseInput = "",
                        backupHistory = refreshedHistory,
                        statusBannerTh = "ส่งออกไฟล์สำรองข้อมูลสำเร็จ: ${result.archiveId} (ขนาด ${result.totalBytesWritten} ไบต์ • SHA-256=${result.header.payloadSha256Hex.take(12)}...)",
                        errorBannerTh = null
                    )
                }
            }

            is BackupExportResult.Rejected -> {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        errorBannerTh = "[${result.errorCode.name}] ${result.messageTh}",
                        statusBannerTh = null
                    )
                }
            }
        }
        return result
    }

    /**
     * ขั้นตอนที่ 1 ของการกู้คืน: ถอดรหัสและตรวจสอบความสมบูรณ์ในพื้นที่ Staging
     */
    fun executeStageAndValidateFromStream(inputStream: InputStream): StagingPreviewResult {
        val current = _uiState.value
        val passChars = current.restorePassphraseInput.toCharArray()
        if (passChars.size < BackupDesignSpec.MIN_PASSPHRASE_LENGTH_CHARS) {
            passChars.fill('\u0000')
            val msg = "กรุณากรอกรหัสผ่านกู้คืนข้อมูลอย่างน้อย ${BackupDesignSpec.MIN_PASSPHRASE_LENGTH_CHARS} ตัวอักษร"
            _uiState.update { it.copy(errorBannerTh = msg, statusBannerTh = null) }
            return StagingPreviewResult.Rejected(
                errorCode = com.oneproject.offline.core.security.BackupRestoreErrorCode.PASSPHRASE_TOO_SHORT,
                messageTh = msg
            )
        }

        // หากมีไฟล์ Staging เดิมค้างอยู่ให้ล้างออกก่อน
        current.stagedPreview?.let { oldPreview ->
            backupManager.discardStagedPreview(oldPreview)
        }

        _uiState.update {
            it.copy(
                isBusy = true,
                stagedPreview = null,
                replaceOnlyConfirmationChecked = false,
                errorBannerTh = null,
                statusBannerTh = null
            )
        }

        val stageResult = backupManager.stageAndValidateArchive(
            inputStream = inputStream,
            passphrase = passChars
        )

        when (stageResult) {
            is StagingPreviewResult.ReadyForReplaceConfirmation -> {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        restorePassphraseInput = "",
                        stagedPreview = stageResult.preview,
                        replaceOnlyConfirmationChecked = false,
                        statusBannerTh = "ตรวจสอบไฟล์สำรองในพื้นที่ Staging ผ่านครบทุกด่าน (AES-GCM Tag + SHA-256 + SQLite Integrity OK) กรุณาตรวจสอบข้อมูลและกดยืนยันการเขียนทับแบบ Replace-Only",
                        errorBannerTh = null
                    )
                }
            }

            is StagingPreviewResult.Rejected -> {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        stagedPreview = null,
                        replaceOnlyConfirmationChecked = false,
                        errorBannerTh = "[${stageResult.errorCode.name}] ${stageResult.messageTh}",
                        statusBannerTh = null
                    )
                }
            }
        }
        return stageResult
    }

    /**
     * ยกเลิกการกู้คืนและลบไฟล์ที่พักไว้ใน Staging
     */
    fun cancelStagedRestore() {
        val currentPreview = _uiState.value.stagedPreview
        if (currentPreview != null) {
            backupManager.discardStagedPreview(currentPreview)
        }
        _uiState.update {
            it.copy(
                stagedPreview = null,
                replaceOnlyConfirmationChecked = false,
                statusBannerTh = "ยกเลิกการกู้คืนและลบไฟล์ในพื้นที่ Staging เรียบร้อยแล้ว (ข้อมูลปัจจุบันไม่เปลี่ยนแปลง)",
                errorBannerTh = null
            )
        }
    }

    /**
     * ขั้นตอนที่ 2 ของการกู้คืน: เขียนทับฐานข้อมูลหลักด้วยไฟล์ Staging ที่ตรวจผ่านแล้วเท่านั้น (Replace-Only, Never Merge)
     */
    suspend fun executeConfirmedReplaceOnlyRestore(): RestoreExecutionResult {
        val current = _uiState.value
        val preview = current.stagedPreview
            ?: return RestoreExecutionResult.Rejected(
                errorCode = com.oneproject.offline.core.security.BackupRestoreErrorCode.SQLITE_INTEGRITY_CHECK_FAILED,
                messageTh = "ยังไม่มีไฟล์สำรองที่ผ่านการตรวจในพื้นที่ Staging",
                rolledBackToPreviousDb = false
            )

        if (!current.replaceOnlyConfirmationChecked) {
            val msg = "กรุณาติ๊กยืนยันว่าเข้าใจว่าการกู้คืนจะเขียนทับข้อมูลเดิมทั้งหมด (Replace-Only • ไม่ผสานข้อมูล)"
            _uiState.update { it.copy(errorBannerTh = msg, statusBannerTh = null) }
            return RestoreExecutionResult.Rejected(
                errorCode = com.oneproject.offline.core.security.BackupRestoreErrorCode.SQLITE_INTEGRITY_CHECK_FAILED,
                messageTh = msg,
                rolledBackToPreviousDb = false
            )
        }

        _uiState.update { it.copy(isBusy = true, errorBannerTh = null, statusBannerTh = null) }
        val restoreResult = backupManager.executeValidatedReplaceOnlyRestore(
            stagedPreview = preview,
            confirmReplaceOnlyOverwrite = true,
            nowEpochMs = clockEpochMsProvider(),
            noteTh = "กู้คืนแบบ Replace-Only จากไฟล์ ${preview.header.archiveId}"
        )

        val refreshedHistory = try {
            bootstrapRepository.getBackupHistory()
        } catch (_: Exception) {
            _uiState.value.backupHistory
        }

        when (restoreResult) {
            is RestoreExecutionResult.Success -> {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        stagedPreview = null,
                        replaceOnlyConfirmationChecked = false,
                        backupHistory = refreshedHistory,
                        statusBannerTh = "กู้คืนฐานข้อมูลแบบ Replace-Only สำเร็จ (รหัสไฟล์=${restoreResult.archiveId}, งวด=${restoreResult.restoredRoundCode}, จำนวนบิล=${restoreResult.restoredBillCount})",
                        errorBannerTh = null
                    )
                }
            }

            is RestoreExecutionResult.Rejected -> {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        stagedPreview = null,
                        replaceOnlyConfirmationChecked = false,
                        errorBannerTh = "[${restoreResult.errorCode.name}] ${restoreResult.messageTh}",
                        statusBannerTh = null
                    )
                }
            }
        }
        return restoreResult
    }
}

class BackupAndRestoreViewModel(
    backupManager: OfflineBackupAndRestoreManager,
    bootstrapRepository: OfflineBootstrapRepository
) : ViewModel() {

    val controller = BackupAndRestoreWorkflowController(
        backupManager = backupManager,
        bootstrapRepository = bootstrapRepository,
        scope = viewModelScope
    )

    val uiState: StateFlow<BackupAndRestoreUiState> = controller.uiState

    fun updateExportPassphrase(value: String) = controller.updateExportPassphrase(value)
    fun updateExportNote(value: String) = controller.updateExportNote(value)
    fun updateRestorePassphrase(value: String) = controller.updateRestorePassphrase(value)
    fun setReplaceOnlyConfirmationChecked(checked: Boolean) =
        controller.setReplaceOnlyConfirmationChecked(checked)
    fun clearBanners() = controller.clearBanners()
    fun cancelStagedRestore() = controller.cancelStagedRestore()

    fun exportToStreamAsync(outputStream: OutputStream) {
        viewModelScope.launch {
            outputStream.use { out ->
                controller.executeExportToStream(out)
            }
        }
    }

    fun stageAndValidateFromStreamAsync(inputStream: InputStream) {
        viewModelScope.launch {
            inputStream.use { input ->
                controller.executeStageAndValidateFromStream(input)
            }
        }
    }

    fun confirmReplaceOnlyRestoreAsync() {
        viewModelScope.launch {
            controller.executeConfirmedReplaceOnlyRestore()
        }
    }

    companion object {
        fun factory(
            backupManager: OfflineBackupAndRestoreManager,
            bootstrapRepository: OfflineBootstrapRepository
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return BackupAndRestoreViewModel(
                        backupManager = backupManager,
                        bootstrapRepository = bootstrapRepository
                    ) as T
                }
            }
        }
    }
}
