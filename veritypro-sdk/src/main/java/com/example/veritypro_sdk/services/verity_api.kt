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
import retrofit2.http.Path
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
        @Header("x-api-key") apiKey: String
    ): BeginLivenessResponse

    @GET("/kycintegration/country/get-country-document")
    suspend fun getCountryDocuments(
        @Header("x-api-key") apiKey: String,
        @Header("Integrationid") integrationId: String
    ): ApiResponse<List<CountryData>>

    @GET
    suspend fun getLivenessResult(
        @retrofit2.http.Url url: String
    ): LivenessResultResponse

    @POST
    suspend fun triggerLivenessPoll(
        @retrofit2.http.Url url: String
    ): LivenessResultResponse

    // ── Address Verification ──

    @POST("/kycintegration/address-verification/add-verification")
    suspend fun createAddressVerification(
        @Body data: AddAddressVerificationRequest,
        @Header("x-api-key") apiKey: String
    ): ApiResponse<AddressVerificationResponse>

    @POST("/kycintegration/address-verification/update-address-verification")
    @Multipart
    suspend fun updateAddressVerification(
        @Part("SessionId") sessionId: RequestBody,
        @Part("DocumentType") documentType: RequestBody,
        @Part addressDocument: MultipartBody.Part?,
        @Part("PlatformUsed") platformUsed: RequestBody,
        @Part("DeviceAndBrowser") deviceAndBrowser: RequestBody,
        @Part("IpAddress") ipAddress: RequestBody,
        @Part("IpLocation") ipLocation: RequestBody
    ): ApiResponse<AddressVerificationResponse>

    // ── EDD ──

    @POST("/kycintegration/edd/cases")
    @Multipart
    suspend fun createEddCase(
        @Part("SubjectId") subjectId: RequestBody,
        @Part("SubjectName") subjectName: RequestBody,
        @Part("DocumentType") documentType: RequestBody,
        @Part file: MultipartBody.Part,
        @Header("x-api-key") apiKey: String
    ): EddCaseResponse

    @GET("/kycintegration/edd/cases/{caseId}/status")
    suspend fun getEddCaseStatus(
        @Path("caseId") caseId: String,
        @Header("x-api-key") apiKey: String
    ): ApiResponse<EddCaseStatusResponse>
}