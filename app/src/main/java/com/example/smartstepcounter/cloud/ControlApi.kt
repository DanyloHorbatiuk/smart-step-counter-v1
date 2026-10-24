package com.example.smartstepcounter.cloud

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * Lab 4: plain REST calls on the remote control node. Changes are *received* through
 * the SSE stream in [CloudControlListener]; these calls only create and reset the node.
 */
interface ControlApi {
    /** Body is null when the node does not exist yet (Firebase answers `null`). */
    @GET("devices/{deviceId}/control.json")
    suspend fun getControl(@Path("deviceId") deviceId: String): Response<ControlState>

    @PUT("devices/{deviceId}/control.json")
    suspend fun putControl(
        @Path("deviceId") deviceId: String,
        @Body control: ControlState,
    ): Response<ControlState>

    /** Writes back vibrate=false so the command fires only once. */
    @PUT("devices/{deviceId}/control/vibrate.json")
    suspend fun putVibrate(
        @Path("deviceId") deviceId: String,
        @Body value: Boolean,
    ): Response<Boolean>
}
