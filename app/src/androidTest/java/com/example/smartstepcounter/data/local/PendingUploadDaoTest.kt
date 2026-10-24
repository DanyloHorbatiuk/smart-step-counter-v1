package com.example.smartstepcounter.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingUploadDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: PendingUploadDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        dao = db.pendingUploadDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun queueIsFifo() = runTest {
        listOf("a", "b", "c").forEach { dao.insert(PendingUpload(createdAt = 0, payloadJson = it)) }

        val taken = mutableListOf<String>()
        while (true) {
            val head = dao.peekOldest() ?: break
            taken += head.payloadJson
            dao.deleteById(head.id)
        }

        assertEquals(listOf("a", "b", "c"), taken)
        assertNull(dao.peekOldest())
    }

    @Test
    fun peekDoesNotRemove() = runTest {
        dao.insert(PendingUpload(createdAt = 0, payloadJson = "a"))
        dao.peekOldest()
        assertEquals(1, dao.size())
        assertEquals(1, dao.count().first())
    }

    @Test
    fun trimToMaxDropsOldest() = runTest {
        (1..5).forEach { dao.insert(PendingUpload(createdAt = it.toLong(), payloadJson = "$it")) }

        assertEquals(2, dao.trimToMax(3))
        assertEquals("3", dao.peekOldest()?.payloadJson)
        assertEquals(3, dao.size())
    }
}
