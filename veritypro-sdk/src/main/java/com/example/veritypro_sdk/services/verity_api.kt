package com.example.veritypro_sdk.services

import com.example.veritypro_sdk.utils.DataPayload
import com.example.veritypro_sdk.utils.VerityOption
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
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

    // 180 s, not the client-wide 60 s. This call uploads the document images and the
    // liveness video, then blocks while the server runs the verification pipeline —
    // and the integration service alone allows its call to the KYC engine 120 s
    // ("F-18: Increased to 120s to accommodate large video uploads"). Waiting only
    // 60 s aborted submissions the server was still completing: observed on device as
    // SocketTimeoutException at readResponseHeaders 82 s in, discarding a liveness
    // result that had already SUCCEEDED at 99.63 confidence.
    //
    // 180 s = the server's 120 s upstream budget plus headroom for its own work and a
    // slow uplink. Do NOT convert this into a retry: the submit is not idempotent and
    // retrying it has already caused duplicate uploads once (same F-18 note).
    @Headers("${PerCallTimeoutInterceptor.TIMEOUT_HEADER}: 180")
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
        @Part("SecurityAssessmentJson") SecurityAssessmentJson: RequestBody? = null,
        @Part PortraitVideo: MultipartBody.Part? = null,
        @Part DocumentVideo: MultipartBody.Part? = null,
        @Header("x-api-key") apiKey: String
    ): ApiResponse<String>


    @POST("/kycintegration/kyc-verification/begin-liveness")
    suspend fun beginLiveness(
        @Query("sessionId") sessionId: String,
        @Header("x-api-key") apiKey: String,
    ): BeginLivenessResponse

    @POST("/kycintegration/kyc-verification/begin-liveness")
    suspend fun beginLivenessWithAssessment(
        @Query("sessionId") sessionId: String,
        @Header("x-api-key") apiKey: String,
        @Body assessment: BeginLivenessAssessmentRequest,
    ): BeginLivenessResponse

    /** Step-up begin-liveness — authenticated by API key OR capability token bearer. Retrofit omits null headers. */
    @POST("/kycintegration/api/v1/step-up/challenges/{challengeId}/begin-liveness")
    suspend fun beginStepUpLiveness(
        @Path("challengeId") challengeId: String,
        @Header("x-api-key") apiKey: String? = null,
        @Header("Authorization") authorization: String? = null,
    ): StepUpBeginResponse

    @GET("/kycintegration/country/get-country-document")
    suspend fun getCountryDocuments(
        @Header("x-api-key") apiKey: String,
        @Header("Integrationid") integrationId: String
    ): ApiResponse<List<CountryData>>

    @GET
    suspend fun getLivenessResult(
        @retrofit2.http.Url url: String,
        @Header("x-api-key") apiKey: String
    ): LivenessResultResponse

    @POST
    suspend fun triggerLivenessPoll(
        @retrofit2.http.Url url: String,
        @Header("x-api-key") apiKey: String
    ): LivenessResultResponse

    // ── Address Verification ──

    // Backend-proxied address autocomplete (Google Places server-side; SDK holds no Google key).
    @GET("/addressverification/api/v1/address/autocomplete")
    suspend fun addressAutocomplete(
        @Query("q") query: String,
        @Query("country") country: String?,
        @Query("sessionToken") sessionToken: String?,
        @Header("x-api-key") apiKey: String
    ): ApiResponse<AddressAutocompleteResponse>

    @GET("/addressverification/api/v1/address/details")
    suspend fun addressDetails(
        @Query("placeId") placeId: String,
        @Query("sessionToken") sessionToken: String?,
        @Header("x-api-key") apiKey: String
    ): ApiResponse<AddressDetailsResponse>

    @POST("/addressverification/address-verification/add-verification")
    suspend fun createAddressVerification(
        @Body data: AddAddressVerificationRequest,
        @Header("x-api-key") apiKey: String
    ): ApiResponse<AddressVerificationResponse>

    @POST("/addressverification/address-verification/update-address-verification")
    @Multipart
    suspend fun updateAddressVerification(
        @Part("SessionId") sessionId: RequestBody,
        @Part("DocumentType") documentType: RequestBody,
        @Part addressDocument: MultipartBody.Part?,
        @Part("PlatformUsed") platformUsed: RequestBody,
        @Part("DeviceAndBrowser") deviceAndBrowser: RequestBody,
        @Part("IpAddress") ipAddress: RequestBody,
        @Part("IpLocation") ipLocation: RequestBody,
        @Part("SecurityAssessmentJson") securityAssessmentJson: RequestBody? = null,
        @Part("IdempotencyKey") idempotencyKey: RequestBody? = null,
        @Header("x-api-key") apiKey: String
    ): ApiResponse<String>

    @GET("/addressverification/address-verification/{verificationId}/documents")
    suspend fun getAddressVerificationDocuments(
        @Path("verificationId") verificationId: String,
        @Header("x-api-key") apiKey: String
    ): ApiResponse<List<AddressDocumentFileResponse>>

    @GET("/addressverification/address-verification/{verificationId}/documents/{documentId}/url")
    suspend fun getAddressDocumentUrl(
        @Path("verificationId") verificationId: String,
        @Path("documentId") documentId: String,
        @Header("x-api-key") apiKey: String
    ): ApiResponse<DocumentUrlResponse>

    // ── EDD (routed via VerityPro infrastructure proxy /edd/ → EDD Intelligence API) ──

    @POST("/edd/api/edd/cases")
    @Multipart
    suspend fun createEddCase(
        @Part("SubjectId") subjectId: RequestBody,
        @Part("SubjectName") subjectName: RequestBody,
        @Part("DocumentType") documentType: RequestBody,
        @Part file: MultipartBody.Part,
        @Part("IntegrationId") integrationId: RequestBody? = null,
        @Part("IdempotencyKey") idempotencyKey: RequestBody? = null,
        @Part("PlatformUsed") platformUsed: RequestBody? = null,
        @Part("DeviceAndBrowser") deviceAndBrowser: RequestBody? = null,
        @Part("IpAddress") ipAddress: RequestBody? = null,
        @Part("IpLocation") ipLocation: RequestBody? = null,
        @Part("SecurityAssessmentJson") securityAssessmentJson: RequestBody? = null,
        @Part("City") city: RequestBody? = null,
        @Part("StateOrProvince") stateOrProvince: RequestBody? = null,
        @Part("PostalCode") postalCode: RequestBody? = null,
        @Part("Country") country: RequestBody? = null,
        @Part("KycProfileJson") profile: RequestBody? = null,
        @Header("Authorization") authorization: String? = null,
        @Header("x-api-key") apiKey: String?
    ): EddApiResponse<EddCaseData>

    @GET("/edd/api/edd/cases/{caseId}/status")
    suspend fun getEddCaseStatus(
        @Path("caseId") caseId: String,
        @Header("x-api-key") apiKey: String
    ): ApiResponse<EddCaseStatusResponse>

    @GET("/edd/api/edd/cases/{caseId}/documents")
    suspend fun getEddCaseDocuments(
        @Path("caseId") caseId: String,
        @Header("x-api-key") apiKey: String
    ): ApiResponse<List<EddDocumentResponse>>

    @GET("/edd/api/edd/cases/{caseId}/documents/{documentId}/url")
    suspend fun getEddDocumentUrl(
        @Path("caseId") caseId: String,
        @Path("documentId") documentId: String,
        @Header("x-api-key") apiKey: String
    ): ApiResponse<DocumentUrlResponse>

    // ── v2 Server-Driven Session Endpoints ──

    @POST("/kycintegration/v2/sessions")
    suspend fun createV2Session(
        @Body request: CreateSessionRequest,
        @Header("x-api-key") apiKey: String
    ): ApiResponse<SessionStateResponse>

    @GET("/kycintegration/v2/sessions/{sessionId}")
    suspend fun getV2SessionState(
        @Path("sessionId") sessionId: String,
        @Header("x-api-key") apiKey: String
    ): ApiResponse<SessionStateResponse>

    @POST("/kycintegration/v2/sessions/{sessionId}/steps/{stepName}/complete")
    suspend fun completeV2Step(
        @Path("sessionId") sessionId: String,
        @Path("stepName") stepName: String,
        @Header("x-api-key") apiKey: String,
        @Body body: StepCompletionRequest = StepCompletionRequest()
    ): ApiResponse<SessionStateResponse>

    // ── Biometric Step-Up Authentication ──────────────────────────────────────
    // Route: API gateway → KYC Integration → /api/v1/step-up/...

    @POST("/kycintegration/api/v1/step-up/challenges/{challengeId}/complete")
    suspend fun completeStepUpChallenge(
        @Path("challengeId") challengeId: String,
        @Header("x-api-key") apiKey: String? = null,
        @Header("Authorization") authorization: String? = null,
        @Body request: StepUpCompleteRequest,
    ): StepUpCompletionEnvelope

    @GET("/kycintegration/api/v1/step-up/challenges/{challengeId}")
    suspend fun getStepUpChallengeStatus(
        @Path("challengeId") challengeId: String,
        @Header("x-api-key") apiKey: String? = null,
        @Header("Authorization") authorization: String? = null,
    ): StepUpCompletionEnvelope
}
