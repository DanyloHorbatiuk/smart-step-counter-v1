package com.example.smartstepcounter.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.smartstepcounter.actuator.ActuatorEvent
import com.example.smartstepcounter.actuator.ActuatorType
import com.example.smartstepcounter.actuator.EventSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActuatorEventDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: ActuatorEventDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        dao = db.actuatorEventDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun observeLatestIsNewestFirstAndKeepsEnumNames() = runTest {
        (1L..3L).forEach {
            dao.insert(ActuatorEventEntity.from(ActuatorEvent(it, ActuatorType.TORCH, it % 2 == 1L, EventSource.CLOUD, "r$it")))
        }

        val latest = dao.observeLatest(2).first()

        assertEquals(listOf(3L, 2L), latest.map { it.timestamp })
        assertEquals("TORCH", latest.first().actuator)
        assertEquals("CLOUD", latest.first().source)
        assertEquals(true, latest.first().isOn)
    }
}
