package com.example.smartstepcounter.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CircularBufferTest {

    @Test
    fun emptyBuffer() {
        val buffer = CircularBuffer(3)
        assertEquals(0, buffer.size)
        assertFalse(buffer.isFull)
        assertEquals(0, buffer.snapshot().size)
    }

    @Test
    fun fillsUpInOrder() {
        val buffer = CircularBuffer(3)
        buffer.add(1f)
        buffer.add(2f)
        assertFalse(buffer.isFull)
        assertArrayEquals(floatArrayOf(1f, 2f), buffer.snapshot(), 0f)
        buffer.add(3f)
        assertTrue(buffer.isFull)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), buffer.snapshot(), 0f)
    }

    @Test
    fun overwritesOldestAndKeepsChronologicalOrder() {
        val buffer = CircularBuffer(3)
        (1..5).forEach { buffer.add(it.toFloat()) }
        assertEquals(3, buffer.size)
        assertArrayEquals(floatArrayOf(3f, 4f, 5f), buffer.snapshot(), 0f)
        // Wrap-around more than once.
        (6..10).forEach { buffer.add(it.toFloat()) }
        assertArrayEquals(floatArrayOf(8f, 9f, 10f), buffer.snapshot(), 0f)
    }

    @Test
    fun snapshotIsACopy() {
        val buffer = CircularBuffer(2)
        buffer.add(1f)
        buffer.add(2f)
        val snapshot = buffer.snapshot()
        buffer.add(3f)
        assertArrayEquals(floatArrayOf(1f, 2f), snapshot, 0f)
    }

    @Test
    fun clearEmptiesTheBuffer() {
        val buffer = CircularBuffer(2)
        buffer.add(1f)
        buffer.add(2f)
        buffer.clear()
        assertEquals(0, buffer.size)
        buffer.add(5f)
        assertArrayEquals(floatArrayOf(5f), buffer.snapshot(), 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroCapacityIsRejected() {
        CircularBuffer(0)
    }
}
