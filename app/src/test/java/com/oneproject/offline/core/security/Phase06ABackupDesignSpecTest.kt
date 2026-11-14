package com.oneproject.offline.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit Test สำหรับตรวจสอบข้อกำหนดการออกแบบความปลอดภัยของไฟล์สำรองข้อมูล (Phase 06A)
 */
class Phase06ABackupDesignSpecTest {

    @Test
    fun authenticatedBackupHeader_producesDeterministicCanonicalAad_andChangesWhenAnyFieldTampered() {
        val header = AuthenticatedBackupHeader(
            archiveId = "bk-20261113-001",
            createdAtEpochMs = 1_762_761_600_000L,
            walCheckpointVerified = true,
            saltHex = "00112233445566778899aabbccddeeff",
            ivHex = "00112233445566778899aabb",
            payloadUnencryptedSizeBytes = 65_536L,
            payloadSha256Hex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        )

        val aad1 = header.toCanonicalAadString()
        val aad2 = header.copy().toCanonicalAadString()
        assertEquals(aad1, aad2)
        assertTrue(aad1.contains("magic=1PROJ_BK"))
        assertTrue(aad1.contains("schema=2"))
        assertTrue(aad1.contains("wal=true"))

        // หากผู้ไม่หวังดีแก้ไขรุ่น Schema หรือขนาดไฟล์ ค่า AAD ต้องเปลี่ยนทันทีเพื่อให้ AES-GCM ปฏิเสธการถอดรหัส
        val tamperedSchemaAad = header.copy(roomSchemaVersion = 1).toCanonicalAadString()
        val tamperedWalAad = header.copy(walCheckpointVerified = false).toCanonicalAadString()
        assertNotEquals(aad1, tamperedSchemaAad)
        assertNotEquals(aad1, tamperedWalAad)
    }

    @Test
    fun backupDesignSpec_enforcesVettedPlatformCryptoParameters() {
        assertEquals("PBKDF2WithHmacSHA256", BackupDesignSpec.KDF_ALGORITHM)
        assertTrue(BackupDesignSpec.KDF_ITERATION_COUNT >= 210_000)
        assertEquals(16, BackupDesignSpec.KDF_SALT_LENGTH_BYTES)
        assertEquals(256, BackupDesignSpec.AES_KEY_LENGTH_BITS)
        assertEquals("AES/GCM/NoPadding", BackupDesignSpec.AEAD_CIPHER_TRANSFORMATION)
        assertEquals(12, BackupDesignSpec.GCM_IV_LENGTH_BYTES)
        assertEquals(128, BackupDesignSpec.GCM_TAG_LENGTH_BITS)
    }
}
