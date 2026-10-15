package com.example.smartstepcounter.cloud

import androidx.lifecycle.viewModelScope
import com.example.smartstepcounter.StepCounterUiState
import com.example.smartstepcounter.sensor.MotionState
import com.example.smartstepcounter.sensor.StepSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudSyncViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeCloudRepository()
    private val steps = MutableStateFlow(
        StepCounterUiState(listening = true, snapshot = StepSnapshot(x = 0f, y = 0f, z = 9.8f)),
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** The ticker loops forever, so time is stepped manually and the VM scope cancelled at the end. */
    private fun test(body: suspend TestScope.(CloudSyncViewModel) -> Unit) = runTest(dispatcher) {
        val vm = CloudSyncViewModel(repository, steps, deviceId = "test-device", clock = { currentTime })
        runCurrent()
        try {
            body(vm)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    private fun TestScope.tick() {
        advanceTimeBy(CloudSyncViewModel.TICK_INTERVAL_MS)
        runCurrent()
    }

    private fun snapshot(transform: StepSnapshot.() -> StepSnapshot) =
        steps.update { it.copy(snapshot = it.snapshot.transform()) }

    @Test
    fun unchangedDataIsSentOnlyOnce() = test {
        tick()
        assertEquals(1, repository.uploaded.size)
        tick()
        tick()
        assertEquals(1, repository.uploaded.size)
    }

    @Test
    fun axisChangeAboveDeltaIsSentOnNextTick() = test {
        tick()
        snapshot { copy(x = x + 0.6f) }
        tick()
        assertEquals(2, repository.uploaded.size)
        assertEquals(0.6f, repository.uploaded.last().x, 1e-6f)
    }

    @Test
    fun smallAxisChangeIsNotSent() = test {
        tick()
        snapshot { copy(x = x + 0.3f) }
        tick()
        assertEquals(1, repository.uploaded.size)
    }

    @Test
    fun newStepsAreSentOnNextTick() = test {
        tick()
        snapshot { copy(steps = 1) }
        tick()
        assertEquals(listOf(0, 1), repository.uploaded.map { it.steps })
    }

    @Test
    fun motionStateChangeIsSentImmediatelyButThrottled() = test {
        snapshot { copy(state = MotionState.WALKING) }
        runCurrent()
        assertEquals(listOf("WALKING"), repository.uploaded.map { it.state })

        // A second change within 1 s waits for the rate limit.
        advanceTimeBy(100)
        snapshot { copy(state = MotionState.RUNNING) }
        runCurrent()
        assertEquals(1, repository.uploaded.size)

        advanceTimeBy(CloudSyncViewModel.STATE_MIN_INTERVAL_MS)
        runCurrent()
        assertEquals(listOf("WALKING", "RUNNING"), repository.uploaded.map { it.state })
    }

    @Test
    fun nothingIsSentWhileSensorIsPaused() = test {
        steps.update { it.copy(listening = false) }
        tick()
        assertEquals(0, repository.uploaded.size)
    }
}
