package com.example.veritypro_sdk.services

import com.google.gson.annotations.SerializedName

// ── Address Verification ──

data class AddAddressVerificationRequest(
    val integrationId: String,
    val firstName: String,
    val lastName: String,
    val streetAddress: String,
    val vendorData: String,
    @SerializedName("ISO2Code") val isO2Code: String,
    // The backend requires Street + at least one other component (City/State/Postal/Country).
    // Country always accompanies streetAddress; the rest are filled from address autocomplete.
    val city: String? = null,
    val stateOrProvince: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
)

data class AddressVerificationResponse(
    val sessionId: String,
    val sessionUrl: String,
    val sessionToEncode: String
)

/** Address document types: 1 = Utility Bill, 2 = Account Statement */
data class AddressDocType(val id: Int, val name: String)

// ── Address Autocomplete (backend-proxied Google Places; SDK holds no Google key) ──
// The SDK calls /addressverification/api/v1/address/{autocomplete,details}; the backend
// calls Google server-side. Field names match the backend's camelCase JSON (Gson exact-match).

data class AddressAutocompleteResponse(
    val predictions: List<AddressPrediction> = emptyList(),
    val attribution: String = "Powered by Google",
)

data class AddressPrediction(
    val description: String = "",
    val placeId: String = "",
    val mainText: String? = null,
    val secondaryText: String? = null,
)

data class AddressDetailsResponse(
    val formattedAddress: String? = null,
    val streetNumber: String? = null,
    val route: String? = null,
    val streetAddress: String? = null,
    val unit: String? = null,
    val city: String? = null,
    val state: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
    val countryCode: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

// ── EDD (Enhanced Due Diligence) ──

data class EddCaseResponse(
    // Nullable: a wrapped error / empty body can deserialize to null despite the create contract,
    // so callers must treat a null caseId as failure (never a silent success).
    val caseId: String? = null,
    val status: String? = null
)

/**
 * The EDD create endpoint returns APIResponse<EddCaseDto> where EddCaseDto is {id, status} — NOT a
 * top-level {caseId, status}. This mirrors the `data` payload so the repository can unwrap it and
 * map id → caseId. (status may serialise as a string or an enum ordinal; String tolerates both.)
 */
data class EddCaseData(
    @SerializedName("id") val id: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("caseId") val caseIdAlt: String? = null   // defensive: accept caseId if ever present
)

/**
 * EDD-Intelligence envelope. Unlike the KYC-Integration ApiResponse (statusCode is an int like 200),
 * the EDD API serialises statusCode as the HttpStatusCode NAME ("OK", "BadRequest") — so statusCode
 * must be a String here or Gson throws NumberFormatException("OK") when mapping it to an Int.
 */
data class EddApiResponse<T>(
    @SerializedName("statusCode") val statusCode: String? = null,
    @SerializedName("statusMessage") val statusMessage: String? = null,
    @SerializedName("data") val data: T? = null,
    @SerializedName("error") val error: ApiError? = null
)

data class EddCaseStatusResponse(
    val caseId: String,
    val status: String,
    val verdict: String? = null
)

/**
 * EDD document types:
 * 0 = BankStatement, 1 = PaySlip, 2 = TaxReturn, 3 = UtilityBill,
 * 4 = EmploymentLetter, 5 = BusinessRegistration, 6 = InvestmentStatement,
 * 7 = RentalAgreement, 8 = GovernmentBenefit, 99 = Other
 */
data class EddDocType(val id: Int, val name: String)

// ── Document list/URL responses ──

data class AddressDocumentFileResponse(
    val id: String,
    val documentType: String,
    val fileName: String,
    val cloudinaryUrl: String? = null,
    val cloudinaryPublicId: String? = null,
    val fileSizeBytes: Long = 0,
    val mimeType: String? = null,
    val ocrExtractedText: String? = null,
    val ocrConfidence: Double? = null,
    val forensicScore: Double? = null,
    val isForensicPass: Boolean? = null,
    val createdAt: String? = null
)

data class EddDocumentResponse(
    val id: String,
    val documentType: String,
    val fileName: String,
    val fileUrl: String? = null,
    val cloudinaryUrl: String? = null,
    val cloudinaryPublicId: String? = null,
    val fileSize: Long = 0,
    val mimeType: String? = null,
    val ocrExtractedText: String? = null,
    val ocrConfidence: Double? = null,
    val uploadedAt: String? = null
)

data class DocumentUrlResponse(
    val url: String
)
