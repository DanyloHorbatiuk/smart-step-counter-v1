package com.example.smartstepcounter.data.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** In-memory DAOs with the same semantics as the Room queries. */
class FakeSensorRecordDao : SensorRecordDao {
    val rows = MutableStateFlow<List<SensorRecord>>(emptyList())
    private var nextId = 1L

    /** Thrown by the next insert, then cleared: simulates SQLite errors. */
    var failNextInsert: Exception? = null

    override suspend fun insert(record: SensorRecord): Long {
        failNextInsert?.let { failNextInsert = null; throw it }
        val id = nextId++
        rows.update { it + record.copy(id = id) }
        return id
    }

    override fun observeLatest(limit: Int): Flow<List<SensorRecord>> =
        rows.map { list -> list.sortedWith(ORDER).takeLast(limit) }

    override fun count(): Flow<Int> = rows.map { it.size }

    override suspend fun deleteOlderThan(timestamp: Long): Int = remove { it.timestamp < timestamp }

    override suspend fun trimToMaxRows(max: Int): Int {
        val keep = rows.value.sortedWith(ORDER).takeLast(max).map { it.id }.toSet()
        return remove { it.id !in keep }
    }

    override suspend fun clear() {
        rows.value = emptyList()
    }

    private fun remove(predicate: (SensorRecord) -> Boolean): Int {
        val before = rows.value.size
        rows.update { list -> list.filterNot(predicate) }
        return before - rows.value.size
    }

    private companion object {
        val ORDER = compareBy<SensorRecord>({ it.timestamp }, { it.id })
    }
}

class FakeActuatorEventDao : ActuatorEventDao {
    val rows = MutableStateFlow<List<ActuatorEventEntity>>(emptyList())
    private var nextId = 1L

    override suspend fun insert(event: ActuatorEventEntity): Long {
        val id = nextId++
        rows.update { it + event.copy(id = id) }
        return id
    }

    override fun observeLatest(limit: Int): Flow<List<ActuatorEventEntity>> =
        rows.map { list -> list.sortedWith(compareByDescending<ActuatorEventEntity> { it.timestamp }.thenByDescending { it.id }).take(limit) }

    override suspend fun deleteOlderThan(timestamp: Long): Int {
        val before = rows.value.size
        rows.update { list -> list.filterNot { it.timestamp < timestamp } }
        return before - rows.value.size
    }

    override suspend fun clear() {
        rows.value = emptyList()
    }
}

/** Shared between repository instances to simulate a table that survives an app restart. */
class FakePendingUploadDao : PendingUploadDao {
    val rows = MutableStateFlow<List<PendingUpload>>(emptyList())
    private var nextId = 1L

    override suspend fun insert(upload: PendingUpload): Long {
        val id = nextId++
        rows.update { it + upload.copy(id = id) }
        return id
    }

    override suspend fun peekOldest(): PendingUpload? = rows.value.minByOrNull { it.id }

    override suspend fun deleteById(id: Long): Int {
        val before = rows.value.size
        rows.update { list -> list.filterNot { it.id == id } }
        return before - rows.value.size
    }

    override fun count(): Flow<Int> = rows.map { it.size }

    override suspend fun size(): Int = rows.value.size

    override suspend fun trimToMax(max: Int): Int {
        val keep = rows.value.sortedByDescending { it.id }.take(max).map { it.id }.toSet()
        val before = rows.value.size
        rows.update { list -> list.filter { it.id in keep } }
        return before - rows.value.size
    }
}
