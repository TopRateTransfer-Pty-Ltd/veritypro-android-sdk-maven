package com.example.veritypro_sdk.services

import com.example.veritypro_sdk.utils.DataPayload
import com.example.veritypro_sdk.utils.VerityOption
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.PartMap
import retrofit2.http.Query

interface VerityApiService {
    @POST("/kycintegration/kyc-verification/add-kyc-verification")
    suspend fun createKyc(
        @Body data: DataPayload, @Header("x-api-key") apiKey: String,
    ): ApiResponse<SessionData>

    @POST("/kycintegration/kyc-verification/update-kyc-verification")
    @Multipart
    suspend fun updateKyc(
        @Part("SessionId") SessionId: RequestBody,
        @Part("DocumentType") DocumentType: RequestBody,
        @Part("PlatformUsed") PlatformUsed: RequestBody,
        @Part("DeviceAndBrowser") DeviceAndBrowser: RequestBody,
        @Part("IpAddress") IpAddress: RequestBody,
        @Part("IpLocation") IpLocation: RequestBody,
        @Part PortraitPicture: MultipartBody.Part?,
        @Part DocumentFront: MultipartBody.Part?,
        @Part("LivenessId") LivenessId: RequestBody,
        @Part DocumentBack: MultipartBody.Part?,
        @Header("x-api-key") apiKey: String
    ): ApiResponse<String>


    @POST("/kycintegration/kyc-verification/begin-liveness")
    suspend fun beginLiveness(
        @Query("sessionId") sessionId: String,
    ): BeginLivenessResponse

    @GET("/kycintegration/country/get-country-document")
    suspend fun getCountryDocuments(
        @Header("x-api-key") apiKey: String,
        @Header("Integrationid") integrationId: String
    ): ApiResponse<List<CountryData>>

    @GET
    suspend fun getLivenessResult(
        @retrofit2.http.Url url: String,
        @Query("sessionId") sessionId: String
    ): LivenessResultResponse
}