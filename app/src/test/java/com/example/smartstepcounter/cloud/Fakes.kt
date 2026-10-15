package com.example.smartstepcounter.cloud

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import java.io.IOException

class FakeNetworkMonitor(online: Boolean) : NetworkMonitor {
    override val online = MutableStateFlow(online)
}

class FakeFirebaseApi : FirebaseApi {
    enum class Mode { OK, IO_ERROR, HTTP_500 }

    var mode = Mode.OK
    val posted = mutableListOf<SensorData>()
    val statuses = mutableListOf<DeviceStatus>()

    override suspend fun postReading(deviceId: String, data: SensorData): Response<PushResponse> =
        when (mode) {
            Mode.OK -> {
                posted += data
                Response.success(PushResponse("-push${posted.size}"))
            }
            Mode.IO_ERROR -> throw IOException("timeout")
            Mode.HTTP_500 -> Response.error(500, "{\"error\":\"boom\"}".toResponseBody())
        }

    override suspend fun putStatus(deviceId: String, status: DeviceStatus): Response<DeviceStatus> {
        statuses += status
        return Response.success(status)
    }
}

class FakeCloudRepository : ICloudRepository {
    val uploaded = mutableListOf<SensorData>()
    override val state: StateFlow<CloudSyncState> = MutableStateFlow(CloudSyncState()).asStateFlow()
    override val events: SharedFlow<CloudEvent> = MutableSharedFlow()

    override suspend fun upload(data: SensorData) {
        uploaded += data
    }
}

fun reading(ts: Long, steps: Int = 0) = SensorData(
    deviceId = "test-device",
    timestamp = ts,
    value = 9.8f,
    x = 0f, y = 0f, z = 9.8f,
    steps = steps,
    cadence = 0f,
    state = "STILL",
    rms = 0f,
)
