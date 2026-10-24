package com.example.smartstepcounter

import android.content.Context
import android.util.Log
import com.example.smartstepcounter.actuator.Actuator
import com.example.smartstepcounter.actuator.ControlSettings
import com.example.smartstepcounter.actuator.SmartActuator
import com.example.smartstepcounter.ble.MockBleConnector
import com.example.smartstepcounter.ble.MockBleScanner
import com.example.smartstepcounter.cloud.AndroidNetworkMonitor
import com.example.smartstepcounter.cloud.CloudCommandHandler
import com.example.smartstepcounter.cloud.CloudControlListener
import com.example.smartstepcounter.cloud.CloudRepository
import com.example.smartstepcounter.cloud.DeviceIdProvider
import com.example.smartstepcounter.cloud.FirebaseApi
import com.example.smartstepcounter.cloud.FirebaseApiFactory
import com.example.smartstepcounter.cloud.ICloudRepository
import com.example.smartstepcounter.cloud.IControlListener
import com.example.smartstepcounter.cloud.NetworkMonitor
import com.example.smartstepcounter.cloud.NotConfiguredCloudRepository
import com.example.smartstepcounter.cloud.NotConfiguredControlListener
import com.example.smartstepcounter.data.local.AppDatabase
import com.example.smartstepcounter.history.SensorHistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual DI: app-wide singletons. The BLE link and the cloud queue outlive screens and
 * configuration changes, so they live here rather than in a ViewModel.
 */
class AppContainer(context: Context) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Lab 5: one Room database per process; Room opens it lazily on the first query (off the main thread).
    val database: AppDatabase = AppDatabase.create(context)

    val connector = MockBleConnector(appScope)
    val scanner = MockBleScanner(connector)

    // Lab 3: cloud sync.
    val deviceId: String = DeviceIdProvider.get(context)
    val networkMonitor: NetworkMonitor = AndroidNetworkMonitor(context)

    /** Null when FIREBASE_DB_URL is missing from local.properties. */
    val api: FirebaseApi? = FirebaseApiFactory.create(BuildConfig.FIREBASE_DB_URL, debug = BuildConfig.DEBUG)

    val cloudRepository: ICloudRepository =
        api?.let { CloudRepository(it, networkMonitor, database.pendingUploadDao(), appScope) }
            ?: NotConfiguredCloudRepository().also {
                Log.w(CloudRepository.TAG, "FIREBASE_DB_URL is not set, cloud sync disabled")
            }

    // Lab 4: actuators and Cloud-to-Device control.
    val actuator: Actuator = SmartActuator(context)
    val controlSettings = ControlSettings()

    val controlListener: IControlListener = createControlListener()

    // Lab 5: local history. Subscribed right after the actuator is created, so its replay cache is empty.
    val historyRepository = SensorHistoryRepository(
        sensorDao = database.sensorRecordDao(),
        actuatorDao = database.actuatorEventDao(),
    ).also { it.start(appScope, actuator.events) }

    private fun createControlListener(): IControlListener {
        val baseUrl = FirebaseApiFactory.normalizeUrl(BuildConfig.FIREBASE_DB_URL)
        val controlApi = FirebaseApiFactory.createControlApi(BuildConfig.FIREBASE_DB_URL, debug = BuildConfig.DEBUG)
        if (baseUrl == null || controlApi == null) return NotConfiguredControlListener()
        val handler = CloudCommandHandler(
            actuator = actuator,
            api = controlApi,
            deviceId = deviceId,
            mirrorToTorch = { controlSettings.mirrorLedToTorch.value },
        )
        return CloudControlListener(
            streamUrl = baseUrl.resolve("devices/$deviceId/control.json")!!,
            api = controlApi,
            deviceId = deviceId,
            networkMonitor = networkMonitor,
            scope = appScope,
            onCommand = handler::handle,
        ).also { it.start() }
    }
}
