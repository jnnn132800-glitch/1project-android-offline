package com.oneproject.offline.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.oneproject.offline.data.local.entity.RoundStatus
import com.oneproject.offline.data.repository.OfflineBootstrapRepository
import com.oneproject.offline.domain.usecase.EnsureFirstLaunchBootstrapUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ShellUiState(
    val isBootstrapping: Boolean = true,
    val roundCode: String = "กำลังโหลด...",
    val roundTitleTh: String = "",
    val roundStatus: RoundStatus = RoundStatus.DRAFT,
    val policyVersion: Long = 1L,
    val topRateExact: String = "60",
    val bottomRateExact: String = "60",
    val receiveCapMinor: Long = 100_000L,
    val isDemoDefaultPolicy: Boolean = true,
    val auditCount: Int = 0,
    val firstLaunchSeededNow: Boolean = false
)

class MainViewModel(
    private val repository: OfflineBootstrapRepository,
    private val ensureFirstLaunchBootstrapUseCase: EnsureFirstLaunchBootstrapUseCase
) : ViewModel() {

    private val bootstrapSeededNow = MutableStateFlow(false)
    private val bootstrapDone = MutableStateFlow(false)

    val uiState: StateFlow<ShellUiState> = combine(
        repository.observeLatestRound(),
        repository.observeActivePolicy(),
        repository.observeAuditCount(),
        bootstrapSeededNow,
        bootstrapDone
    ) { round, policy, auditCount, seededNow, done ->
        if (round == null) {
            ShellUiState(isBootstrapping = !done)
        } else {
            ShellUiState(
                isBootstrapping = !done,
                roundCode = round.roundCode,
                roundTitleTh = round.titleTh,
                roundStatus = RoundStatus.fromStorageValue(round.status),
                policyVersion = policy?.policyVersion ?: round.activePolicyVersion,
                topRateExact = policy?.defaultTopRateExact ?: "60",
                bottomRateExact = policy?.defaultBottomRateExact ?: "60",
                receiveCapMinor = policy?.defaultReceiveCapMinor ?: 100_000L,
                isDemoDefaultPolicy = policy?.isDemoDefault ?: round.isDemoSeed,
                auditCount = auditCount,
                firstLaunchSeededNow = seededNow
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ShellUiState()
    )

    init {
        viewModelScope.launch {
            val result = ensureFirstLaunchBootstrapUseCase()
            bootstrapSeededNow.value = result.seededInitialDemoData
            bootstrapDone.value = true
        }
    }

    companion object {
        fun factory(
            repository: OfflineBootstrapRepository,
            useCase: EnsureFirstLaunchBootstrapUseCase
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MainViewModel(repository, useCase) as T
            }
        }
    }
}
