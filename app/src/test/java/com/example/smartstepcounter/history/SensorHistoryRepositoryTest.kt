package com.example.smartstepcounter.history

import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import com.example.smartstepcounter.actuator.ActuatorEvent
import com.example.smartstepcounter.actuator.ActuatorType
import com.example.smartstepcounter.actuator.EventSource
import com.example.smartstepcounter.data.local.FakeActuatorEventDao
import com.example.smartstepcounter.data.local.FakeSensorRecordDao
import com.example.smartstepcounter.data.local.SensorRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SensorHistoryRepositoryTest {

    private val sensorDao = FakeSensorRecordDao()
    private val actuatorDao = FakeActuatorEventDao()
    private var now = NOW

    private fun TestScope.newRepository() = SensorHistoryRepository(
        sensorDao = sensorDao,
        actuatorDao = actuatorDao,
        io = StandardTestDispatcher(testScheduler),
        clock = { now },
        logger = {},
    )

    @Test
    fun retentionDeletesRecordsOlderThan24h() = runTest {
        val repo = newRepository()
        repo.insertRecord(record(NOW - RetentionPolicy.MAX_AGE_MS - 1))
        repo.insertRecord(record(NOW - RetentionPolicy.MAX_AGE_MS + 1))
        repo.insertRecord(record(NOW))

        repo.applyRetention()

        assertEquals(2, sensorDao.rows.value.size)
        assertTrue(sensorDao.rows.value.all { it.timestamp >= RetentionPolicy.cutoff(NOW) })
    }

    @Test
    fun retentionKeepsNewestMaxRows() = runTest {
        val repo = newRepository()
        sensorDao.rows.value = (1..RetentionPolicy.MAX_ROWS + 5).map { record(NOW - 100_000 + it, id = it.toLong()) }

        repo.applyRetention()

        assertEquals(RetentionPolicy.MAX_ROWS, sensorDao.rows.value.size)
        assertEquals(6L, sensorDao.rows.value.minOf { it.id })
    }

    @Test
    fun cleanupRunsEveryMinute() = runTest {
        val repo = newRepository()
        repo.start(backgroundScope, MutableSharedFlow())
        runCurrent()
        repo.insertRecord(record(NOW))

        now += RetentionPolicy.MAX_AGE_MS + 1
        advanceTimeBy(RetentionPolicy.CLEANUP_INTERVAL_MS)
        runCurrent()

        assertTrue(sensorDao.rows.value.isEmpty())
    }

    @Test
    fun actuatorEventsAreStored() = runTest {
        val repo = newRepository()
        val events = MutableSharedFlow<ActuatorEvent>()
        repo.start(backgroundScope, events)
        runCurrent()

        events.emit(ActuatorEvent(NOW, ActuatorType.VIBRATION, on = true, source = EventSource.AUTO, reason = "R1"))
        runCurrent()

        val stored = repo.observeActuatorEvents(20).first().single()
        assertEquals("VIBRATION", stored.actuator)
        assertEquals("AUTO", stored.source)
        assertEquals("R1", stored.reason)
    }

    @Test
    fun diskFullTrimsAggressivelyAndReports() = runTest {
        val repo = newRepository()
        val events = mutableListOf<StorageEvent>()
        backgroundScope.launch { repo.events.collect { events += it } }
        runCurrent()
        sensorDao.rows.value = (1..3_000).map { record(NOW - 3_000 + it, id = it.toLong()) }
        sensorDao.failNextInsert = SQLiteFullException("database or disk is full")

        repo.insertRecord(record(NOW)) // must not throw
        runCurrent()

        assertEquals(StorageStatus.FULL, repo.storageState.value.status)
        assertEquals(RetentionPolicy.AGGRESSIVE_KEEP_ROWS, sensorDao.rows.value.size)
        assertEquals(listOf<StorageEvent>(StorageEvent.SpaceFreed(1_000)), events)
    }

    @Test
    fun otherSqliteErrorSetsErrorUntilNextCleanPass() = runTest {
        val repo = newRepository()
        sensorDao.failNextInsert = SQLiteException("disk I/O error")

        repo.insertRecord(record(NOW))
        assertEquals(StorageStatus.ERROR, repo.storageState.value.status)

        repo.applyRetention()
        assertEquals(StorageStatus.OK, repo.storageState.value.status)
    }

    @Test
    fun recordIntervalIsClamped() = runTest {
        val repo = newRepository()
        assertEquals(SensorHistoryRepository.DEFAULT_RECORD_INTERVAL_MS, repo.recordIntervalMs.value)
        repo.setRecordInterval(250)
        assertEquals(250L, repo.recordIntervalMs.value)
        repo.setRecordInterval(1)
        assertEquals(SensorHistoryRepository.MIN_RECORD_INTERVAL_MS, repo.recordIntervalMs.value)
    }

    @Test
    fun clearHistoryEmptiesBothTables() = runTest {
        val repo = newRepository()
        repo.insertRecord(record(NOW))
        repo.insertActuatorEvent(ActuatorEvent(NOW, ActuatorType.TORCH, on = true, source = EventSource.MANUAL, reason = "x"))

        repo.clearHistory()

        assertEquals(0, repo.observeCount().first())
        assertTrue(actuatorDao.rows.value.isEmpty())
    }

    private fun record(timestamp: Long, id: Long = 0) = SensorRecord(
        id = id,
        timestamp = timestamp,
        sensorValue = 9.8f,
        x = 0f, y = 0f, z = 9.8f,
        rms = 0f,
        steps = 0,
        state = "STILL",
    )

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
