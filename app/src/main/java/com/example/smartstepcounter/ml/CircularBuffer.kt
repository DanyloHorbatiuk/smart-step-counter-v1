package com.example.smartstepcounter.ml

/**
 * Fixed-size ring buffer of floats: once full, every [add] overwrites the oldest value.
 * Pure Kotlin, not thread-safe.
 */
class CircularBuffer(val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be positive: $capacity" }
    }

    private val data = FloatArray(capacity)

    /** Index the next value is written to. */
    private var head = 0

    var size = 0
        private set

    val isFull: Boolean get() = size == capacity

    fun add(value: Float) {
        data[head] = value
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    fun clear() {
        head = 0
        size = 0
    }

    /** Copy of the stored values, oldest first. */
    fun snapshot(): FloatArray {
        val start = if (isFull) head else 0
        return FloatArray(size) { data[(start + it) % capacity] }
    }
}
