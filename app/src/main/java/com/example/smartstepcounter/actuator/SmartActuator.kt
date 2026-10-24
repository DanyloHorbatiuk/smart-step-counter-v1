package com.example.smartstepcounter.actuator

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Real actuators: the vibration motor and the camera flash used as a torch.
 *
 * Without a flash (most emulators) the torch degrades to a virtual one: the state is kept
 * and shown in the UI, nothing is sent to the hardware. App-wide singleton (AppContainer),
 * so the torch callback is registered once.
 */
class SmartActuator(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : Actuator {

    private val vibrator: Vibrator =
        context.getSystemService(VibratorManager::class.java).defaultVibrator

    private val cameraManager: CameraManager? = context.getSystemService(CameraManager::class.java)

    /** Null = no hardware flash, the torch is virtual. */
    @Volatile
    private var torchCameraId: String? = findTorchCamera(context)

    private val _state = MutableStateFlow(
        ActuatorState(
            torchIsVirtual = torchCameraId == null,
            vibratorAvailable = vibrator.hasVibrator(),
        )
    )
    override val state: StateFlow<ActuatorState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ActuatorEvent>(replay = EVENT_REPLAY, extraBufferCapacity = 16)
    override val events: SharedFlow<ActuatorEvent> = _events.asSharedFlow()

    /** Keeps [ActuatorState.torchOn] in sync with the real torch (other apps, the system tile). */
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId != torchCameraId) return
            if (_state.value.torchOn != enabled) Log.d(TAG, "torch changed outside the app: on=$enabled")
            _state.update { it.copy(torchOn = enabled) }
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            // The camera is busy (e.g. another app opened it): the torch is off for now.
            if (cameraId == torchCameraId) _state.update { it.copy(torchOn = false) }
        }
    }

    init {
        Log.i(TAG, "vibrator=${vibrator.hasVibrator()}, torch camera=${torchCameraId ?: "none (virtual)"}")
        if (torchCameraId != null) {
            cameraManager?.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
        }
    }

    override fun triggerAlarm(pattern: VibrationPattern, source: EventSource, reason: String) {
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern.timings, NO_REPEAT))
        } else {
            Log.w(TAG, "no vibrator, vibration $pattern is only logged")
        }
        val now = clock()
        _state.update { it.copy(lastVibrationAt = now) }
        emit(ActuatorType.VIBRATION, on = true, source, "$reason [$pattern]", now)
    }

    override fun toggleFlashlight(on: Boolean, source: EventSource, reason: String) {
        val id = torchCameraId
        if (id != null) {
            try {
                cameraManager?.setTorchMode(id, on)
            } catch (e: CameraAccessException) {
                fallBackToVirtual("setTorchMode failed: ${e.message}")
            } catch (e: IllegalArgumentException) {
                fallBackToVirtual("camera $id is gone: ${e.message}")
            }
        }
        // The callback confirms the hardware state too; setting it here keeps the virtual torch working.
        _state.update { it.copy(torchOn = on) }
        val suffix = if (_state.value.torchIsVirtual) " (віртуальний)" else ""
        emit(ActuatorType.TORCH, on, source, reason + suffix)
    }

    override fun setVirtualLamp(on: Boolean, source: EventSource, reason: String) {
        _state.update { it.copy(virtualLampOn = on) }
        emit(ActuatorType.LAMP, on, source, reason)
    }

    private fun fallBackToVirtual(why: String) {
        Log.w(TAG, "$why -> virtual torch")
        torchCameraId = null
        cameraManager?.unregisterTorchCallback(torchCallback)
        _state.update { it.copy(torchIsVirtual = true) }
    }

    private fun emit(type: ActuatorType, on: Boolean, source: EventSource, reason: String, now: Long = clock()) {
        val event = ActuatorEvent(now, type, on, source, reason)
        Log.i(TAG, "$type ${if (on) "ON" else "OFF"} source=$source reason=$reason")
        _events.tryEmit(event)
    }

    private fun findTorchCamera(context: Context): String? {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            Log.w(TAG, "FEATURE_CAMERA_FLASH is missing -> virtual torch")
            return null
        }
        val cm = cameraManager ?: return null
        return try {
            cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }.also { if (it == null) Log.w(TAG, "no camera reports FLASH_INFO_AVAILABLE -> virtual torch") }
        } catch (e: CameraAccessException) {
            Log.w(TAG, "camera list unavailable: ${e.message} -> virtual torch")
            null
        }
    }

    companion object {
        const val TAG = "Actuator"

        /** The log on the "Керування" tab shows this many recent events. */
        const val EVENT_REPLAY = 30

        private const val NO_REPEAT = -1
    }
}
