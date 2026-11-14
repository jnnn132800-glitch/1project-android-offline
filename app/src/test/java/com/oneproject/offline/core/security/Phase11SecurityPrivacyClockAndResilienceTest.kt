package com.oneproject.offline.core.security

import com.oneproject.offline.ui.navigation.OneProjectDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase11SecurityPrivacyClockAndResilienceTest {

    @Test
    fun saltedPinCreationAndVerification_neverStoresPlaintextAndLocksOutAfterMaxFailures() {
        val rawPin = "246810"
        val now = 1_710_000_000_000L

        val record = LocalAppLockAndGuardrailsEngine.createSaltedPinRecord(
            rawPin = rawPin,
            role = LocalOperationalRole.SUPERVISOR,
            nowEpochMs = now
        )

        // Never store plaintext PIN in salt or derived hash
        assertFalse(record.saltBase64.contains(rawPin))
        assertFalse(record.derivedHashBase64.contains(rawPin))
        assertNotEquals(rawPin, record.derivedHashBase64)

        // Correct PIN verifies
        val verified = LocalAppLockAndGuardrailsEngine.verifyPin(
            candidatePin = rawPin,
            record = record,
            nowEpochMs = now + 1_000L
        )
        assertTrue(verified is PinVerificationResult.Verified)

        // 5 consecutive wrong attempts trigger lockout
        var currentRecord = record
        for (attempt in 1..4) {
            val res = LocalAppLockAndGuardrailsEngine.verifyPin(
                candidatePin = "000000",
                record = currentRecord,
                nowEpochMs = now + attempt * 1_000L
            )
            assertTrue(res is PinVerificationResult.InvalidPin)
            currentRecord = (res as PinVerificationResult.InvalidPin).updatedRecord
            assertEquals(attempt, currentRecord.failedAttempts)
        }

        val fifthResult = LocalAppLockAndGuardrailsEngine.verifyPin(
            candidatePin = "000000",
            record = currentRecord,
            nowEpochMs = now + 5_000L
        )
        assertTrue(fifthResult is PinVerificationResult.LockedOut)
        val lockedRecord = (fifthResult as PinVerificationResult.LockedOut).let {
            currentRecord.copy(
                failedAttempts = 5,
                lockedUntilEpochMs = it.lockedUntilEpochMs
            )
        }

        // Even with the right PIN during lockout window, access remains locked out
        val duringLockout = LocalAppLockAndGuardrailsEngine.verifyPin(
            candidatePin = rawPin,
            record = lockedRecord,
            nowEpochMs = now + 10_000L
        )
        assertTrue(duringLockout is PinVerificationResult.LockedOut)

        // After lockout window expires, correct PIN succeeds and resets failedAttempts
        val afterLockout = LocalAppLockAndGuardrailsEngine.verifyPin(
            candidatePin = rawPin,
            record = lockedRecord,
            nowEpochMs = lockedRecord.lockedUntilEpochMs + 1_000L
        )
        assertTrue(afterLockout is PinVerificationResult.Verified)
        assertEquals(0, (afterLockout as PinVerificationResult.Verified).updatedRecord.failedAttempts)
    }

    @Test
    fun privacyLogScrubber_redactsPinsPassphrasesAndSecrets() {
        val rawLog = "Error unlocking: pin=135790, passphrase: SuperSecretBackupKey2026, รหัส PIN 987654"
        val scrubbed = LocalAppLockAndGuardrailsEngine.scrubSensitiveText(rawLog)

        assertFalse(scrubbed.contains("135790"))
        assertFalse(scrubbed.contains("SuperSecretBackupKey2026"))
        assertFalse(scrubbed.contains("987654"))
        assertTrue(scrubbed.contains("[REDACTED_SECRET]"))
        assertTrue(scrubbed.contains("[REDACTED_PIN]"))
    }

    @Test
    fun deviceGuardrails_detectClockRollbackAndLowStorage() {
        val latestMutation = 1_710_500_000_000L

        // 1. Healthy
        val healthy = LocalAppLockAndGuardrailsEngine.evaluateDeviceHealth(
            currentClockEpochMs = latestMutation + 5_000L,
            latestRecordedEpochMs = latestMutation,
            availableStorageBytes = 128L * 1024L * 1024L
        )
        assertEquals(DeviceGuardrailStatus.HEALTHY, healthy.status)
        assertTrue(healthy.canCommitAuthoritativeMutations)

        // 2. Clock rollback beyond 60s tolerance (D12)
        val clockRollback = LocalAppLockAndGuardrailsEngine.evaluateDeviceHealth(
            currentClockEpochMs = latestMutation - 120_000L,
            latestRecordedEpochMs = latestMutation,
            availableStorageBytes = 128L * 1024L * 1024L
        )
        assertEquals(DeviceGuardrailStatus.BLOCKED_CLOCK_ROLLBACK, clockRollback.status)
        assertFalse(clockRollback.canCommitAuthoritativeMutations)

        // 3. Low storage (< 32 MB)
        val lowStorage = LocalAppLockAndGuardrailsEngine.evaluateDeviceHealth(
            currentClockEpochMs = latestMutation + 5_000L,
            latestRecordedEpochMs = latestMutation,
            availableStorageBytes = 12L * 1024L * 1024L
        )
        assertEquals(DeviceGuardrailStatus.BLOCKED_LOW_STORAGE, lowStorage.status)
        assertFalse(lowStorage.canCommitAuthoritativeMutations)
    }

    @Test
    fun accessibilityDestinations_allHaveThaiTitlesAndTalkBackDescriptions() {
        val destinations = OneProjectDestination.entries
        assertEquals(5, destinations.size)
        destinations.forEach { destination ->
            assertTrue(destination.titleTh.isNotBlank())
            assertTrue(destination.talkBackDescription.isNotBlank())
        }
    }
}
