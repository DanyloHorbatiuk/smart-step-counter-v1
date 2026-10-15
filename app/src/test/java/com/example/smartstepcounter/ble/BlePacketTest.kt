package com.example.smartstepcounter.ble

import com.example.smartstepcounter.sensor.MotionState
import com.example.smartstepcounter.sensor.StepSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class BlePacketTest {

    @Test
    fun stepsPacketFormat() {
        val snapshot = StepSnapshot(steps = 28, cadence = 107.6f)
        assertEquals("STEPS=28;CAD=108", BlePacket.steps(snapshot))
    }

    @Test
    fun motionPacketFormatUsesDotDecimalSeparator() {
        val snapshot = StepSnapshot(state = MotionState.WALKING, rms = 1.4189f)
        assertEquals("STATE=WALKING;RMS=1.42", BlePacket.motion(snapshot))
    }

    @Test
    fun sizeIsCountedInUtf8Bytes() {
        assertEquals(16, BlePacket.sizeOf("STEPS=28;CAD=108"))
        assertEquals(4, BlePacket.sizeOf("ÿÿ"))
    }
}
