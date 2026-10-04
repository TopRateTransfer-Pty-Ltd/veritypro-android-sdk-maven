package com.example.veritypro_sdk.services

import android.os.Build
import android.util.Log
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
    /**
     * A session owns its endpoint; configuring one integrator never changes another session.
     * There is no default API: [baseUrl] is the integrator's configured origin, and the client
     * pins that host to the ISRG roots ([VerityEndpoint.ISRG_ROOT_PINS]).
     */
    fun createApi(baseUrl: String): VerityApiService {
        val origin = VerityEndpoint.requireApiOrigin(baseUrl)
        // Retrofit requires every base URL to end in '/'.
        return Retrofit.Builder().baseUrl("$origin/")
            .addConverterFactory(GsonConverterFactory.create())
            .client(VerityEndpoint.pinnedClient(okHttpClient, origin))
            .build().create(VerityApiService::class.java)
    }

    /** Unpinned base; only ever used through [createApi], which adds the pins for its host. */
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(RequestSigningInterceptor())
            // Lets the KYC submit declare its own, longer budget without slowing the
            // failure of every other call. See PerCallTimeoutInterceptor — the 60 s
            // default below is shorter than the server's own 120 s upstream budget, so
            // without this the phone abandons submissions the server then completes.
            .addInterceptor(PerCallTimeoutInterceptor())
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}


/**
 * ML Backend Retrofit Instance
 *
 * DocAI lives on the session's API origin: [configureForApiBaseUrl] points it at `<apiBaseUrl>/docai`.
 * [configure] exists only for local development against a doc-ml process on this machine or LAN.
 * There is no default: using [api] before either is called throws.
 */
object MLRetrofitInstance {

    private const val TAG = "MLRetrofitInstance"

    // Local-development origins accepted by configure(); production goes through configureForApiBaseUrl.
    private val ALLOWED_URL_PREFIXES = listOf(
        "http://10.0.2.2:",      // Android emulator → host localhost
        "http://localhost:",
        "http://127.0.0.1:",
        "http://192.168."        // LAN — local dev on physical device
    )

    @Volatile
    private var mlBaseUrl: String? = null

    // Logging interceptor — HEADERS only to prevent leaking base64 images,
    // API keys, and AWS credentials in logcat. BODY level must NEVER be used
    // in release builds (SaaS B2B/B2C security requirement).
    private val loggingInterceptor = createSafeLoggingInterceptor(
        if (Build.FINGERPRINT?.contains("generic") == true || Build.FINGERPRINT?.contains("emulator") == true) {  // null only off-device (JVM unit tests)
            HttpLoggingInterceptor.Level.HEADERS  // Emulator: headers only (still no body)
        } else {
            HttpLoggingInterceptor.Level.NONE     // Physical device: no logging
        }
    )

    private fun buildOkHttpClient(baseUrl: String): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(RequestSigningInterceptor())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)   // verify-burst needs more read time
            .writeTimeout(30, TimeUnit.SECONDS)   // burst upload can be large
            .callTimeout(60, TimeUnit.SECONDS)    // hard cap — prevents 43s+ hangs without timeout
            .addInterceptor(loggingInterceptor)

        // Every HTTPS origin is pinned to the ISRG roots; only local-dev plain HTTP is not.
        if (baseUrl.startsWith("https://")) {
            builder.certificatePinner(VerityEndpoint.pinnerFor(baseUrl))
        }

        return builder.build()
    }

    @Volatile
    private var retrofit: Retrofit? = null
    @Volatile
    private var mlApiService: MLApiService? = null

    /**
     * Configure the ML backend URL for local development only.
     * URL must be a local-development origin; production DocAI is set by [configureForApiBaseUrl].
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
     * Point the ML client at the DocAI surface of the session's API origin: `<apiBaseUrl>/docai`.
     * Same validation as [RetrofitInstance.createApi] (an HTTPS origin, nothing else), because it is
     * the same integrator-supplied value. Before this the SDK sent ID images to the staging host
     * whatever API the session used.
     */
    fun configureForApiBaseUrl(apiBaseUrl: String) {
        val origin = VerityEndpoint.requireApiOrigin(apiBaseUrl)
        val docai = "$origin/docai"
        synchronized(this) {
            if (mlBaseUrl == docai) return
            mlBaseUrl = docai
            retrofit = null
            mlApiService = null
        }
        Log.d(TAG, "ML backend follows API origin: $docai")
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
                val base = checkNotNull(mlBaseUrl) {
                    "DocAI is not configured: start the SDK with apiBaseUrl (or call configureForApiBaseUrl)"
                }
                val newRetrofit = Retrofit.Builder()
                    // configure() trims the trailing '/'; Retrofit requires the base URL to end in
                    // '/' when it carries a path (e.g. ".../docai") — re-add it here.
                    .baseUrl(if (base.endsWith("/")) base else "$base/")
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(buildOkHttpClient(base))
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
    fun isConfigured(): Boolean = !mlBaseUrl.isNullOrEmpty()

    /**
     * Get current ML backend URL
     */
    fun getBaseUrl(): String? = mlBaseUrl
}
