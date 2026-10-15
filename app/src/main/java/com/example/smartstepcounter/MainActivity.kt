package com.example.smartstepcounter

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.smartstepcounter.ui.StepCounterScreen
import com.example.smartstepcounter.ui.theme.SmartStepCounterTheme

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    private val viewModel: StepCounterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        sensorManager = getSystemService(SensorManager::class.java)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        viewModel.setSensorAvailable(accelerometer != null)

        setContent {
            SmartStepCounterTheme {
                val state = viewModel.uiState.collectAsStateWithLifecycle()
                StepCounterScreen(state = state.value, onReset = viewModel::reset)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            // SENSOR_DELAY_GAME ≈ 50 Hz: enough for step detection and still energy-efficient.
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            viewModel.onListeningChanged(true)
        }
    }

    override fun onPause() {
        // Stop reading the sensor when the app is not visible to save battery.
        sensorManager.unregisterListener(this)
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
}
