package com.example.smartstepcounter.cloud

import com.example.smartstepcounter.sensor.MotionState
import com.example.smartstepcounter.sensor.StepSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncChangeFilterTest {

    private val base = StepSnapshot(x = 0.1f, y = 0.2f, z = 9.8f, steps = 10, state = MotionState.STILL)

    @Test
    fun firstSnapshotIsAlwaysSent() {
        assertTrue(SyncChangeFilter().shouldSend(base))
    }

    @Test
    fun unchangedSnapshotIsNotSent() {
        val filter = SyncChangeFilter().apply { markSent(base) }
        assertFalse(filter.shouldSend(base))
        // Sensor noise below Δ.
        assertFalse(filter.shouldSend(base.copy(x = base.x + 0.4f, y = base.y - 0.3f, z = base.z + 0.2f)))
    }

    @Test
    fun axisChangeAboveDeltaIsSent() {
        val filter = SyncChangeFilter().apply { markSent(base) }
        assertTrue(filter.shouldSend(base.copy(x = base.x + 0.6f)))
        assertTrue(filter.shouldSend(base.copy(y = base.y - 0.6f)))
        assertTrue(filter.shouldSend(base.copy(z = base.z + 0.6f)))
    }

    @Test
    fun newStepsAreSent() {
        val filter = SyncChangeFilter().apply { markSent(base) }
        assertTrue(filter.shouldSend(base.copy(steps = 11)))
    }

    @Test
    fun motionStateChangeIsSent() {
        val filter = SyncChangeFilter().apply { markSent(base) }
        assertTrue(filter.shouldSend(base.copy(state = MotionState.WALKING)))
    }
}
