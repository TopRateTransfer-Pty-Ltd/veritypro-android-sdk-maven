package com.example.veritypro_sdk.services

import android.os.Build
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitInstance {
    private const val BASE_URL = "https://api.skylinefare.com"

    val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val api: VerityApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(okHttpClient)
            .build()
            .create(VerityApiService::class.java)
    }
}


/**
 * ML Backend Retrofit Instance
 *
 * Configurable ML backend for document verification
 * Auto-detects emulator vs physical device:
 * - Emulator: http://10.0.2.2:8001 (maps to host localhost)
 * - Physical device: Must be configured with actual ML backend URL
 */
object MLRetrofitInstance {

    private const val TAG = "MLRetrofitInstance"

    // Detect if running on emulator
    private fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.BRAND.startsWith("generic")
                || Build.DEVICE.startsWith("generic")
                || "google_sdk" == Build.PRODUCT
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu"))
    }

    // Default ML backend URL - auto-detect emulator vs physical device
    private var mlBaseUrl: String = if (isEmulator()) {
        Log.d(TAG, "Running on EMULATOR - using 10.0.2.2:8001")
        //"http://10.0.2.2:8001"
        "https://api.skylinefare.com/docai/"

    } else {
        // Physical device - need actual IP address
        // This should be configured by the app before use
        Log.w(TAG, "Running on PHYSICAL DEVICE - ML backend needs configuration!")
        Log.w(TAG, "Call MLRetrofitInstance.configure('http://YOUR_MACHINE_IP:8001') before using ML features")
        "https://api.skylinefare.com/docai/" // Your development machine's LAN IP
    }

    //https://api.skylinefare.com/docai/v1/kyc/doc/models

    // Logging interceptor for debugging
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val mlOkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .build()

    private var retrofit: Retrofit? = null
    private var mlApiService: MLApiService? = null

    /**
     * Configure the ML backend URL
     * Call this before using the ML API
     *
     * @param baseUrl The ML backend base URL (e.g., "http://192.168.1.100:8001")
     */
    fun configure(baseUrl: String) {
        mlBaseUrl = baseUrl.trimEnd('/')
        retrofit = null
        mlApiService = null
    }

    /**
     * Get the ML API service instance
     */
    val api: MLApiService
        get() {
            if (mlApiService == null) {
                retrofit = Retrofit.Builder()
                    .baseUrl(mlBaseUrl)
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(mlOkHttpClient)
                    .build()
                mlApiService = retrofit!!.create(MLApiService::class.java)
            }
            return mlApiService!!
        }

    /**
     * Check if ML backend is configured and reachable
     */
    fun isConfigured(): Boolean = mlBaseUrl.isNotEmpty()

    /**
     * Get current ML backend URL
     */
    fun getBaseUrl(): String = mlBaseUrl
}