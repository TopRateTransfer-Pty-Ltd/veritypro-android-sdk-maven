package com.example.veritypro_sdk.services

import org.junit.Test

/** Guards the default SDK client from regressing to an invalid Retrofit base URL. */
class RetrofitInstanceContractTest {
    @Test
    fun `default api can be constructed without an http call`() {
        // Accessing the lazy singleton is enough: Retrofit validates the base URL
        // during construction, before any request is sent.
        RetrofitInstance.api
    }
}
