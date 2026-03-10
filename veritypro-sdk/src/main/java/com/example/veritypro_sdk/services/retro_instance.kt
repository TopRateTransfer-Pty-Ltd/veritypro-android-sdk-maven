package com.example.veritypro_sdk.services

import android.os.Build
import android.util.Log
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitInstance {
    private const val BASE_URL = "https://api.skylinefare.com"

    // Certificate pinning for api.skylinefare.com (leaf + intermediate CA)
    private val certificatePinner = CertificatePinner.Builder()
        .add("api.skylinefare.com", "sha256/b2TlY6Y77KRBmvmbQF7jAbNKQErofrz4KXnXDLn2FeI=")
        .add("api.skylinefare.com", "sha256/y7xVm0TVJNahMr2sZydE2jQH8SquXV9yLF9seROHHHU=")
        .build()

    val okHttpClient = OkHttpClient.Builder()
        .certificatePinner(certificatePinner)
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

    // Production ML backend URL — locked to prevent tampering
    private const val ML_BASE_URL = "https://api.skylinefare.com/docai/"

    // Allowed URL prefixes for ML backend (whitelist)
    private val ALLOWED_URL_PREFIXES = listOf(
        "https://api.skylinefare.com/",
        "http://10.0.2.2:",      // Android emulator → host localhost
        "http://localhost:",
        "http://127.0.0.1:"
    )

    private var mlBaseUrl: String = ML_BASE_URL

    // Logging interceptor for debugging
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    // Certificate pinning (same pins as main RetrofitInstance)
    private val certificatePinner = CertificatePinner.Builder()
        .add("api.skylinefare.com", "sha256/b2TlY6Y77KRBmvmbQF7jAbNKQErofrz4KXnXDLn2FeI=")
        .add("api.skylinefare.com", "sha256/y7xVm0TVJNahMr2sZydE2jQH8SquXV9yLF9seROHHHU=")
        .build()

    private fun buildOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)

        // Only apply certificate pinning for production URLs
        if (mlBaseUrl.startsWith("https://api.skylinefare.com")) {
            builder.certificatePinner(certificatePinner)
        }

        return builder.build()
    }

    private var retrofit: Retrofit? = null
    private var mlApiService: MLApiService? = null

    /**
     * Configure the ML backend URL for local development only.
     * URL must match the allowed whitelist (production domain or localhost variants).
     *
     * @param baseUrl The ML backend base URL
     * @throws IllegalArgumentException if URL is not in the allowed whitelist
     */
    fun configure(baseUrl: String) {
        val trimmed = baseUrl.trimEnd('/')
        val isAllowed = ALLOWED_URL_PREFIXES.any { trimmed.startsWith(it) }
        if (!isAllowed) {
            Log.e(TAG, "Rejected ML backend URL: $trimmed — not in allowed whitelist")
            throw IllegalArgumentException(
                "ML backend URL must start with one of: ${ALLOWED_URL_PREFIXES.joinToString()}"
            )
        }
        mlBaseUrl = trimmed
        retrofit = null
        mlApiService = null
        Log.d(TAG, "ML backend configured: $trimmed")
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
                    .client(buildOkHttpClient())
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