package com.oneproject.offline.domain.usecase

import com.oneproject.offline.data.repository.OfflineBootstrapRepository

/**
 * Use Case สำหรับเตรียมความพร้อมเมื่อเปิดแอปครั้งแรกในโหมดเครื่องบิน (Airplane-Mode First Launch)
 * - ไม่มีการเรียกเครือข่าย ไม่มีการล็อกอินคลาวด์ และไม่มีการรีเซ็ตข้อมูลที่มีอยู่แล้ว
 */
class EnsureFirstLaunchBootstrapUseCase(
    private val repository: OfflineBootstrapRepository,
    private val clockEpochMsProvider: () -> Long = { System.currentTimeMillis() }
) {
    suspend operator fun invoke(): FirstLaunchResult {
        val now = clockEpochMsProvider()
        val seededNow = repository.ensureFirstLaunchSeed(now)
        return FirstLaunchResult(
            seededInitialDemoData = seededNow,
            checkedAtEpochMs = now
        )
    }
}

data class FirstLaunchResult(
    val seededInitialDemoData: Boolean,
    val checkedAtEpochMs: Long
)
