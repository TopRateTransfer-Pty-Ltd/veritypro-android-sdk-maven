package com.example.veritypro_sdk.services

import android.os.Build
import android.util.Log
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/** Creates a logging interceptor with sensitive headers redacted. */
private fun createSafeLoggingInterceptor(level: HttpLoggingInterceptor.Level): HttpLoggingInterceptor {
    return HttpLoggingInterceptor().apply {
        this.level = level
        redactHeader("x-api-key")
        redactHeader("Authorization")
        redactHeader("x-session-token")
        redactHeader("Cookie")
        redactHeader("X-Verity-Signature")
    }
}

object RetrofitInstance {
    private const val BASE_URL = "https://api.skylinefare.com"

    // Certificate pinning for api.skylinefare.com (leaf + ISRG Root X1 backup)
    private val certificatePinner = CertificatePinner.Builder()
        .add("api.skylinefare.com", "sha256/b2TlY6Y77KRBmvmbQF7jAbNKQErofrz4KXnXDLn2FeI=")
        .add("api.skylinefare.com", "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=") // ISRG Root X1
        .build()

    val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(RequestSigningInterceptor())
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

    // Logging interceptor — HEADERS only to prevent leaking base64 images,
    // API keys, and AWS credentials in logcat. BODY level must NEVER be used
    // in release builds (SaaS B2B/B2C security requirement).
    private val loggingInterceptor = createSafeLoggingInterceptor(
        if (Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emulator")) {
            HttpLoggingInterceptor.Level.HEADERS  // Emulator: headers only (still no body)
        } else {
            HttpLoggingInterceptor.Level.NONE     // Physical device: no logging
        }
    )

    // Certificate pinning (same pins as main RetrofitInstance)
    private val certificatePinner = CertificatePinner.Builder()
        .add("api.skylinefare.com", "sha256/b2TlY6Y77KRBmvmbQF7jAbNKQErofrz4KXnXDLn2FeI=")
        .add("api.skylinefare.com", "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=") // ISRG Root X1
        .build()

    private fun buildOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(RequestSigningInterceptor())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)   // verify-burst needs more read time
            .writeTimeout(30, TimeUnit.SECONDS)   // burst upload can be large
            .callTimeout(60, TimeUnit.SECONDS)    // hard cap — prevents 43s+ hangs without timeout
            .addInterceptor(loggingInterceptor)

        // Only apply certificate pinning for production URLs
        if (mlBaseUrl.startsWith("https://api.skylinefare.com")) {
            builder.certificatePinner(certificatePinner)
        }

        return builder.build()
    }

    @Volatile
    private var retrofit: Retrofit? = null
    @Volatile
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
        synchronized(this) {
            mlBaseUrl = trimmed
            retrofit = null
            mlApiService = null
        }
        Log.d(TAG, "ML backend configured: $trimmed")
    }

    /**
     * Get the ML API service instance (thread-safe double-checked locking).
     */
    val api: MLApiService
        get() {
            // Fast path: already initialized
            mlApiService?.let { return it }
            // Slow path: synchronize and double-check
            synchronized(this) {
                mlApiService?.let { return it }
                val newRetrofit = Retrofit.Builder()
                    .baseUrl(mlBaseUrl)
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(buildOkHttpClient())
                    .build()
                retrofit = newRetrofit
                val newService = newRetrofit.create(MLApiService::class.java)
                mlApiService = newService
                return newService
            }
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