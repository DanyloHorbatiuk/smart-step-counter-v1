package com.example.smartstepcounter.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SensorRecordDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: SensorRecordDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        dao = db.sensorRecordDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun observeLatestReturnsNewestInChronologicalOrder() = runTest {
        // Inserted out of order on purpose.
        listOf(300L, 100L, 500L, 200L, 400L).forEach { dao.insert(record(it)) }

        val latest = dao.observeLatest(3).first()

        assertEquals(listOf(300L, 400L, 500L), latest.map { it.timestamp })
    }

    @Test
    fun countFollowsInserts() = runTest {
        assertEquals(0, dao.count().first())
        repeat(4) { dao.insert(record(it.toLong())) }
        assertEquals(4, dao.count().first())
    }

    @Test
    fun trimToMaxRowsKeepsNewest() = runTest {
        (1L..10L).forEach { dao.insert(record(it)) }

        val deleted = dao.trimToMaxRows(4)

        assertEquals(6, deleted)
        assertEquals(listOf(7L, 8L, 9L, 10L), dao.observeLatest(100).first().map { it.timestamp })
    }

    @Test
    fun deleteOlderThanRemovesOnlyOldRows() = runTest {
        (1L..5L).forEach { dao.insert(record(it * 1_000)) }

        val deleted = dao.deleteOlderThan(3_000)

        assertEquals(2, deleted)
        assertEquals(listOf(3_000L, 4_000L, 5_000L), dao.observeLatest(100).first().map { it.timestamp })
    }

    @Test
    fun actionFlagIsStored() = runTest {
        dao.insert(record(1).copy(actionTriggered = true))
        assertEquals(true, dao.observeLatest(1).first().single().actionTriggered)
    }

    private fun record(timestamp: Long) = SensorRecord(
        timestamp = timestamp,
        sensorValue = 9.8f,
        x = 0.1f, y = 0.2f, z = 9.7f,
        rms = 0.3f,
        steps = 1,
        state = "WALKING",
    )
}
