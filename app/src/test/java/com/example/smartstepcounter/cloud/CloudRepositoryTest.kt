package com.example.smartstepcounter.cloud

import com.example.smartstepcounter.data.local.FakePendingUploadDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudRepositoryTest {

    private val api = FakeFirebaseApi()
    private val queue = FakePendingUploadDao()

    private fun TestScope.newRepository(network: NetworkMonitor) = CloudRepository(
        api = api,
        networkMonitor = network,
        queue = queue,
        scope = backgroundScope,
        io = StandardTestDispatcher(testScheduler),
        clock = { 1_000L },
        logger = {},
    )

    @Test
    fun onlineUploadSucceeds() = runTest {
        val repo = newRepository(FakeNetworkMonitor(online = true))
        repo.upload(reading(1))

        val state = repo.state.value
        assertEquals(CloudStatus.SUCCESS, state.status)
        assertEquals(1, state.sentCount)
        assertEquals(0, state.pendingCount)
        assertEquals(1_000L, state.lastSuccessAt)
        assertEquals(listOf(1L), api.posted.map { it.timestamp })
        assertEquals(1, api.statuses.size)
    }

    @Test
    fun offlineUploadIsQueuedWithoutCallingApi() = runTest {
        val repo = newRepository(FakeNetworkMonitor(online = false))
        repo.upload(reading(1))
        repo.upload(reading(2))

        assertEquals(CloudStatus.PENDING, repo.state.value.status)
        assertEquals(2, repo.state.value.pendingCount)
        assertTrue(api.posted.isEmpty())
    }

    @Test
    fun networkReturnFlushesQueueInOrder() = runTest {
        val network = FakeNetworkMonitor(online = false)
        val repo = newRepository(network)
        val events = mutableListOf<CloudEvent>()
        backgroundScope.launch { repo.events.collect { events += it } }
        runCurrent()

        (1L..3L).forEach { repo.upload(reading(it)) }
        network.online.value = true
        runCurrent()

        assertEquals(listOf(1L, 2L, 3L), api.posted.map { it.timestamp })
        assertEquals(0, repo.state.value.pendingCount)
        assertEquals(3, repo.state.value.sentCount)
        assertEquals(CloudStatus.SUCCESS, repo.state.value.status)
        assertEquals(listOf<CloudEvent>(CloudEvent.Restored(3)), events)
    }

    @Test
    fun goingOfflineEmitsEventAndPending() = runTest {
        val network = FakeNetworkMonitor(online = true)
        val repo = newRepository(network)
        val events = mutableListOf<CloudEvent>()
        backgroundScope.launch { repo.events.collect { events += it } }
        runCurrent()

        network.online.value = false
        runCurrent()

        assertEquals(CloudStatus.PENDING, repo.state.value.status)
        assertEquals(listOf<CloudEvent>(CloudEvent.WentOffline), events)
    }

    @Test
    fun ioErrorKeepsRecordInQueue() = runTest {
        val repo = newRepository(FakeNetworkMonitor(online = true))
        api.mode = FakeFirebaseApi.Mode.IO_ERROR
        repo.upload(reading(1))

        assertEquals(CloudStatus.ERROR, repo.state.value.status)
        assertEquals(1, repo.state.value.pendingCount)
        assertEquals(0, repo.state.value.sentCount)
        assertTrue(repo.state.value.lastError!!.contains("IOException"))
    }

    @Test
    fun http500KeepsRecordInQueueAndRetriesLater() = runTest {
        val repo = newRepository(FakeNetworkMonitor(online = true))
        api.mode = FakeFirebaseApi.Mode.HTTP_500
        repo.upload(reading(1))

        assertEquals(CloudStatus.ERROR, repo.state.value.status)
        assertEquals("HTTP 500", repo.state.value.lastError)
        assertEquals(1, repo.state.value.pendingCount)

        // The failed record goes out first on the next attempt.
        api.mode = FakeFirebaseApi.Mode.OK
        repo.upload(reading(2))
        assertEquals(listOf(1L, 2L), api.posted.map { it.timestamp })
        assertEquals(CloudStatus.SUCCESS, repo.state.value.status)
        assertEquals(0, repo.state.value.pendingCount)
    }

    @Test
    fun queueDropsOldestWhenFull() = runTest {
        val network = FakeNetworkMonitor(online = false)
        val repo = newRepository(network)
        (1L..CloudRepository.MAX_PENDING + 5L).forEach { repo.upload(reading(it)) }
        assertEquals(CloudRepository.MAX_PENDING, repo.state.value.pendingCount)

        network.online.value = true
        runCurrent()
        assertEquals(6L, api.posted.first().timestamp)
        assertEquals(CloudRepository.MAX_PENDING, api.posted.size)
    }

    @Test
    fun queueSurvivesRestart() = runTest {
        // First run: offline, three records land in the table.
        val firstRun = newRepository(FakeNetworkMonitor(online = false))
        (1L..3L).forEach { firstRun.upload(reading(it)) }
        assertEquals(3, queue.rows.value.size)
        assertTrue(api.posted.isEmpty())

        // "Restart": a new instance over the same table, now with network.
        val secondRun = newRepository(FakeNetworkMonitor(online = true))
        runCurrent()

        assertEquals(listOf(1L, 2L, 3L), api.posted.map { it.timestamp })
        assertTrue(queue.rows.value.isEmpty())
        assertEquals(CloudStatus.SUCCESS, secondRun.state.value.status)
        assertEquals(3, secondRun.state.value.sentCount)
    }

    @Test
    fun restoredQueueIsReportedWhileOffline() = runTest {
        val firstRun = newRepository(FakeNetworkMonitor(online = false))
        (1L..2L).forEach { firstRun.upload(reading(it)) }

        val secondRun = newRepository(FakeNetworkMonitor(online = false))
        runCurrent()

        assertEquals(CloudStatus.PENDING, secondRun.state.value.status)
        assertEquals(2, secondRun.state.value.pendingCount)
    }

    @Test
    fun rowIsDeletedOnlyAfterSuccess() = runTest {
        val repo = newRepository(FakeNetworkMonitor(online = true))
        api.mode = FakeFirebaseApi.Mode.HTTP_500
        repo.upload(reading(1))
        assertEquals(1, queue.rows.value.size)

        api.mode = FakeFirebaseApi.Mode.OK
        repo.upload(reading(2))
        assertTrue(queue.rows.value.isEmpty())
    }
}
