package com.example.smartstepcounter

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.smartstepcounter.actuator.ControlViewModel
import com.example.smartstepcounter.analytics.AnalyticsViewModel
import com.example.smartstepcounter.ble.BleViewModel
import com.example.smartstepcounter.cloud.CloudEvent
import com.example.smartstepcounter.cloud.CloudSyncViewModel
import com.example.smartstepcounter.history.StorageEvent
import com.example.smartstepcounter.ui.AnalyticsActions
import com.example.smartstepcounter.ui.AnalyticsScreen
import com.example.smartstepcounter.ui.ControlActions
import com.example.smartstepcounter.ui.ControlScreen
import com.example.smartstepcounter.ui.DeviceActions
import com.example.smartstepcounter.ui.DeviceScreen
import com.example.smartstepcounter.ui.MainScaffold
import com.example.smartstepcounter.ui.StepCounterScreen
import com.example.smartstepcounter.ui.theme.SmartStepCounterTheme

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    private val viewModel: StepCounterViewModel by viewModels()

    // Lazy delegate: the step ViewModel is resolved first and handed to the BLE layer.
    private val bleViewModel: BleViewModel by viewModels {
        BleViewModel.factory(
            container = (application as SmartStepApp).container,
            steps = viewModel.uiState,
            onHubReset = viewModel::reset,
        )
    }

    private val cloudViewModel: CloudSyncViewModel by viewModels {
        CloudSyncViewModel.factory(
            container = (application as SmartStepApp).container,
            steps = viewModel.uiState,
        )
    }

    private val controlViewModel: ControlViewModel by viewModels {
        ControlViewModel.factory(
            container = (application as SmartStepApp).container,
            samples = viewModel.samples,
        )
    }

    // Lab 5: also owns the sensor recorder, so it is touched in setContent on every start.
    private val analyticsViewModel: AnalyticsViewModel by viewModels {
        AnalyticsViewModel.factory(
            container = (application as SmartStepApp).container,
            steps = viewModel.uiState,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        sensorManager = getSystemService(SensorManager::class.java)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        viewModel.setSensorAvailable(accelerometer != null)

        setContent {
            SmartStepCounterTheme {
                val state = viewModel.uiState.collectAsStateWithLifecycle()
                val bleState = bleViewModel.uiState.collectAsStateWithLifecycle()
                val cloudState = cloudViewModel.state.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }
                LaunchedEffect(Unit) {
                    cloudViewModel.events.collect { event ->
                        val message = when (event) {
                            CloudEvent.WentOffline -> "Немає інтернету — дані збережено в черзі (Pending)"
                            is CloudEvent.Restored -> "Мережу відновлено, відправлено ${event.sent} записів"
                        }
                        snackbarHostState.showSnackbar(message)
                    }
                }
                LaunchedEffect(Unit) {
                    controlViewModel.ruleEvents.collect { snackbarHostState.showSnackbar(it) }
                }
                LaunchedEffect(Unit) {
                    analyticsViewModel.events.collect { event ->
                        when (event) {
                            is StorageEvent.SpaceFreed -> snackbarHostState.showSnackbar("Мало місця: старі записи видалено")
                        }
                    }
                }
                MainScaffold(
                    sensorTab = {
                        StepCounterScreen(
                            state = state.value,
                            cloud = cloudState.value,
                            onReset = viewModel::reset,
                        )
                    },
                    deviceTab = {
                        DeviceScreen(
                            state = bleState.value,
                            actions = DeviceActions(
                                onPermissionsResult = bleViewModel::onPermissionsResult,
                                onScan = bleViewModel::scan,
                                onConnect = bleViewModel::connect,
                                onSendNow = bleViewModel::sendStepsNow,
                                onHubReset = bleViewModel::hubReset,
                                onHubPing = bleViewModel::hubPing,
                                onSimulateLoss = bleViewModel::simulateLoss,
                                onDisconnect = bleViewModel::disconnect,
                            ),
                        )
                    },
                    controlTab = {
                        ControlScreen(
                            viewModel = controlViewModel,
                            actions = ControlActions(
                                onTorch = controlViewModel::toggleTorch,
                                onVibrate = controlViewModel::vibrate,
                                onAutoMode = controlViewModel::setAutoMode,
                                onMirror = controlViewModel::setMirrorLedToTorch,
                            ),
                        )
                    },
                    analyticsTab = {
                        val analytics = analyticsViewModel.uiState.collectAsStateWithLifecycle()
                        AnalyticsScreen(
                            state = analytics.value,
                            actions = AnalyticsActions(
                                onToggleSeries = analyticsViewModel::toggleSeries,
                                onClearHistory = analyticsViewModel::clearHistory,
                            ),
                        )
                    },
                    snackbarHostState = snackbarHostState,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            // SENSOR_DELAY_GAME ≈ 50 Hz: enough for step detection and still energy-efficient.
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.d(LIFECYCLE_TAG, "listener registered")
            viewModel.onListeningChanged(true)
        }
    }

    override fun onPause() {
        // Stop reading the sensor when the app is not visible to save battery.
        sensorManager.unregisterListener(this)
        Log.d(LIFECYCLE_TAG, "listener unregistered")
        viewModel.onListeningChanged(false)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        viewModel.onSample(event.values[0], event.values[1], event.values[2], event.timestamp)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        viewModel.onAccuracyChanged(accuracy)
    }

    private companion object {
        const val LIFECYCLE_TAG = "SensorLifecycle"
    }
}
