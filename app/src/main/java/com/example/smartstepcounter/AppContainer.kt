package com.example.smartstepcounter

import com.example.smartstepcounter.ble.MockBleConnector
import com.example.smartstepcounter.ble.MockBleScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual DI: app-wide singletons. The BLE link outlives screens and
 * configuration changes, so it lives here rather than in a ViewModel.
 */
class AppContainer {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val connector = MockBleConnector(appScope)
    val scanner = MockBleScanner(connector)
}
