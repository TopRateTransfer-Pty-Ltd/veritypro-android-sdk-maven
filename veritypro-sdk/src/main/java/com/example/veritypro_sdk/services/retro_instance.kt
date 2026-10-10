package com.example.veritypro_sdk.services

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

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

