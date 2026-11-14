package com.oneproject.offline.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation Test สำหรับตรวจสอบ Room Database จริงบนอุปกรณ์/Emulator ในโหมดออฟไลน์
 */
@RunWith(AndroidJUnit4::class)
class AirplaneModeFirstLaunchRoomTest {

    private lateinit var database: OneProjectDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(
            context,
            OneProjectDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun bootstrapDemoSeedIfEmpty_insertsRoundPolicyAndAuditInSingleTransaction_andSkipsOnReRun() = runBlocking {
        val dao = database.bootstrapDao()
        val now = 1_762_761_600_000L

        val firstRunSeeded = dao.bootstrapDemoSeedIfEmpty(now)
        assertTrue(firstRunSeeded)
        assertEquals(1, dao.countRounds())

        val round = dao.observeLatestRound().first()
        assertNotNull(round)
        assertEquals("DEMO-2026-001", round?.roundCode)

        val policy = dao.observePolicyVersion(round!!.roundId, round.activePolicyVersion).first()
        assertNotNull(policy)
        assertEquals("60", policy?.defaultTopRateExact)
        assertEquals("HALF_UP", policy?.roundingMode)

        val secondRunSeeded = dao.bootstrapDemoSeedIfEmpty(now + 5_000L)
        assertFalse(secondRunSeeded)
        assertEquals(1, dao.countRounds())
        assertEquals(1, dao.observeAuditCount().first())
    }
}
