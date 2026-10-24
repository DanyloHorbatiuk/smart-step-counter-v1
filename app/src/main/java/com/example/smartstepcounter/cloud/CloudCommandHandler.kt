package com.example.smartstepcounter.cloud

import android.util.Log
import com.example.smartstepcounter.actuator.Actuator
import com.example.smartstepcounter.actuator.EventSource
import com.example.smartstepcounter.actuator.VibrationPattern
import kotlinx.coroutines.CancellationException

/**
 * Executes commands from the control node on the [Actuator].
 *
 * @param mirrorToTorch when true, led_status also drives the real torch.
 */
class CloudCommandHandler(
    private val actuator: Actuator,
    private val api: ControlApi,
    private val deviceId: String,
    private val mirrorToTorch: () -> Boolean,
    private val logger: (String) -> Unit = { Log.d(CloudControlListener.TAG, it) },
) {
    suspend fun handle(command: ControlCommand) {
        when (command) {
            is ControlCommand.SetLed -> {
                val reason = "led_status=${command.on}"
                actuator.setVirtualLamp(command.on, EventSource.CLOUD, reason)
                if (mirrorToTorch()) actuator.toggleFlashlight(command.on, EventSource.CLOUD, "$reason (дубль)")
            }
            ControlCommand.Vibrate -> {
                actuator.triggerAlarm(VibrationPattern.SINGLE, EventSource.CLOUD, "vibrate=true")
                resetVibrate()
            }
        }
    }

    /** Makes the command one-shot; the stream then delivers vibrate=false, which is a no-op. */
    private suspend fun resetVibrate() {
        try {
            val response = api.putVibrate(deviceId, false)
            logger("vibrate reset: ${if (response.isSuccessful) "ok" else "HTTP ${response.code()}"}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger("vibrate reset failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
