package com.example.smartstepcounter.cloud

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

/**
 * Firebase Realtime Database REST API. Every path ends with ".json".
 * Methods return [Response] so HTTP errors (e.g. 500) are seen via [Response.isSuccessful]
 * instead of exceptions; network failures and timeouts still throw IOException.
 */
interface FirebaseApi {
    /** Appends a reading under a generated push id. */
    @POST("devices/{deviceId}/readings.json")
    suspend fun postReading(
        @Path("deviceId") deviceId: String,
        @Body data: SensorData,
    ): Response<PushResponse>

    /** Overwrites the latest device status. */
    @PUT("devices/{deviceId}/status.json")
    suspend fun putStatus(
        @Path("deviceId") deviceId: String,
        @Body status: DeviceStatus,
    ): Response<DeviceStatus>
}

object FirebaseApiFactory {
    private const val TIMEOUT_S = 10L

    /**
     * @param baseUrl database URL, e.g. https://xxx-default-rtdb.europe-west1.firebasedatabase.app
     * @param debug adds a BODY-level HTTP logger (debug builds only).
     * @return null when [baseUrl] is blank or not a valid http(s) URL.
     */
    fun create(baseUrl: String, debug: Boolean): FirebaseApi? {
        val normalized = baseUrl.trim().let { if (it.endsWith("/")) it else "$it/" }
        val url = normalized.takeIf { baseUrl.isNotBlank() }?.toHttpUrlOrNull() ?: return null

        val client = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .apply {
                if (debug) {
                    addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
                }
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FirebaseApi::class.java)
    }
}
