package com.example.smartstepcounter

import android.content.Context
import android.util.Log
import com.example.smartstepcounter.ble.MockBleConnector
import com.example.smartstepcounter.ble.MockBleScanner
import com.example.smartstepcounter.cloud.AndroidNetworkMonitor
import com.example.smartstepcounter.cloud.CloudRepository
import com.example.smartstepcounter.cloud.DeviceIdProvider
import com.example.smartstepcounter.cloud.FirebaseApi
import com.example.smartstepcounter.cloud.FirebaseApiFactory
import com.example.smartstepcounter.cloud.ICloudRepository
import com.example.smartstepcounter.cloud.NetworkMonitor
import com.example.smartstepcounter.cloud.NotConfiguredCloudRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual DI: app-wide singletons. The BLE link and the cloud queue outlive screens and
 * configuration changes, so they live here rather than in a ViewModel.
 */
class AppContainer(context: Context) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val connector = MockBleConnector(appScope)
    val scanner = MockBleScanner(connector)

    // Lab 3: cloud sync.
    val deviceId: String = DeviceIdProvider.get(context)
    val networkMonitor: NetworkMonitor = AndroidNetworkMonitor(context)

    /** Null when FIREBASE_DB_URL is missing from local.properties. */
    val api: FirebaseApi? = FirebaseApiFactory.create(BuildConfig.FIREBASE_DB_URL, debug = BuildConfig.DEBUG)

    val cloudRepository: ICloudRepository =
        api?.let { CloudRepository(it, networkMonitor, appScope) }
            ?: NotConfiguredCloudRepository().also {
                Log.w(CloudRepository.TAG, "FIREBASE_DB_URL is not set, cloud sync disabled")
            }
}
