package com.example.smartstepcounter.history

import com.example.smartstepcounter.StepCounterUiState
import com.example.smartstepcounter.actuator.ActuatorEvent
import com.example.smartstepcounter.actuator.ActuatorType
import com.example.smartstepcounter.actuator.EventSource
import com.example.smartstepcounter.data.local.FakeActuatorEventDao
import com.example.smartstepcounter.data.local.FakeSensorRecordDao
import com.example.smartstepcounter.sensor.StepSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SensorRecorderTest {

    private val sensorDao = FakeSensorRecordDao()
    private val steps = MutableStateFlow(StepCounterUiState(listening = true, snapshot = snapshot(0)))
    private val actuatorEvents = MutableSharedFlow<ActuatorEvent>(replay = 5)

    private lateinit var repository: SensorHistoryRepository

    private fun TestScope.startRecorder() {
        repository = SensorHistoryRepository(
            sensorDao = sensorDao,
            actuatorDao = FakeActuatorEventDao(),
            io = StandardTestDispatcher(testScheduler),
            clock = { currentTime },
            logger = {},
        )
        val recorder = SensorRecorder(repository, steps, actuatorEvents, clock = { currentTime })
        backgroundScope.launch { recorder.run() }
        runCurrent()
    }

    /** Moves time by [ms] and runs everything due at the new time. */
    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun recordsOncePerIntervalWhenDataChanges() = runTest {
        startRecorder()
        advance(999)
        assertTrue(sensorDao.rows.value.isEmpty())

        advance(1)
        assertEquals(1, sensorDao.rows.value.size)

        steps.value = steps.value.copy(snapshot = snapshot(1))
        advance(1_000)
        assertEquals(2, sensorDao.rows.value.size)
        assertEquals(listOf(1_000L, 2_000L), sensorDao.rows.value.map { it.timestamp })
        assertFalse(sensorDao.rows.value.any { it.actionTriggered })
    }

    @Test
    fun unchangedSnapshotIsNotRecordedAgain() = runTest {
        startRecorder()
        advance(5_000)
        assertEquals(1, sensorDao.rows.value.size)

        // Below SyncChangeFilter.AXIS_DELTA: noise, not stored.
        steps.value = steps.value.copy(snapshot = snapshot(0).copy(x = 0.1f))
        advance(1_000)
        assertEquals(1, sensorDao.rows.value.size)
    }

    @Test
    fun pausedSensorIsNotRecorded() = runTest {
        steps.value = steps.value.copy(listening = false)
        startRecorder()
        advance(3_000)
        assertTrue(sensorDao.rows.value.isEmpty())
    }

    @Test
    fun actuatorEventForcesRecordWithFlag() = runTest {
        startRecorder()
        advance(1_000)
        assertEquals(1, sensorDao.rows.value.size)

        // The snapshot is unchanged, the filter would skip it, but an action is always stored.
        advance(300)
        actuatorEvents.emit(event(currentTime))
        runCurrent()

        val last = sensorDao.rows.value.last()
        assertEquals(2, sensorDao.rows.value.size)
        assertTrue(last.actionTriggered)
        assertEquals(1_300L, last.timestamp)
    }

    @Test
    fun replayedActuatorEventsAreIgnored() = runTest {
        advanceTimeBy(500)
        actuatorEvents.emit(event(timestamp = 100)) // happened before the recorder started
        startRecorder()
        assertTrue(sensorDao.rows.value.isEmpty())
    }

    @Test
    fun intervalChangeAppliesImmediately() = runTest {
        startRecorder()
        repository.setRecordInterval(200)
        runCurrent()
        repeat(5) { i ->
            steps.value = steps.value.copy(snapshot = snapshot(i + 1))
            advance(200)
        }
        assertEquals(5, sensorDao.rows.value.size)
        assertEquals(listOf(200L, 400L, 600L, 800L, 1_000L), sensorDao.rows.value.map { it.timestamp })
    }

    private fun snapshot(steps: Int) = StepSnapshot(x = 0f, y = 0f, z = 9.8f, magnitude = 9.8f, steps = steps)

    private fun event(timestamp: Long) =
        ActuatorEvent(timestamp, ActuatorType.TORCH, on = true, source = EventSource.MANUAL, reason = "test")
}
