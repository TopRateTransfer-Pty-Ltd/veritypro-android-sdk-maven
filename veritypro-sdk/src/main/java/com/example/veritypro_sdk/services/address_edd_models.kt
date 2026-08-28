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

// ── EDD (Enhanced Due Diligence) ──

data class EddCaseResponse(
    // Nullable: a wrapped error / empty body can deserialize to null despite the create contract,
    // so callers must treat a null caseId as failure (never a silent success).
    val caseId: String? = null,
    val status: String? = null
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
