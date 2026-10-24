package com.example.smartstepcounter.actuator

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * User switches of the "Керування" tab. App-wide (AppContainer) because cloud commands
 * are handled in the app scope, outside any ViewModel.
 */
class ControlSettings {
    /** When off, DecisionEngine decisions are only logged. */
    val autoMode = MutableStateFlow(true)

    /** When on, led_status from Firebase also switches the real torch. */
    val mirrorLedToTorch = MutableStateFlow(false)
}
