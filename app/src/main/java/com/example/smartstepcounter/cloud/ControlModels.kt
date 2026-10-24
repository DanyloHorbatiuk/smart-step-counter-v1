package com.example.smartstepcounter.cloud

import com.google.gson.annotations.SerializedName

/** Remote control node, devices/{deviceId}/control. Field names match the database keys. */
data class ControlState(
    @SerializedName("led_status") val ledStatus: Boolean = false,
    /** One-shot command: the app vibrates once and writes false back. */
    @SerializedName("vibrate") val vibrate: Boolean = false,
)

enum class ControlListenerStatus {
    /** FIREBASE_DB_URL is missing: no stream is opened. */
    NOT_CONFIGURED,

    /** The SSE request is being opened. */
    CONNECTING,

    /** The stream is open; commands arrive immediately. */
    LISTENING,

    /** The stream dropped or there is no network; waiting before the next attempt. */
    RECONNECTING,

    /** Firebase cancelled the stream (cancel / auth_revoked) or the server rejected it. */
    ERROR,
}

data class ControlListenerState(
    val status: ControlListenerStatus = ControlListenerStatus.CONNECTING,
    /** Wall-clock time of the last command that changed something, ms since epoch. */
    val lastCommandAt: Long? = null,
    val lastError: String? = null,
    /** Last known content of the control node. */
    val control: ControlState = ControlState(),
)

/** An action requested by a change of the control node. */
sealed interface ControlCommand {
    data class SetLed(val on: Boolean) : ControlCommand

    data object Vibrate : ControlCommand
}
