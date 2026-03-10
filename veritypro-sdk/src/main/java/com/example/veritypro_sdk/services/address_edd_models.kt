package com.example.veritypro_sdk.services

import com.google.gson.annotations.SerializedName

// ── Address Verification ──

data class AddAddressVerificationRequest(
    val integrationId: String,
    val firstName: String,
    val lastName: String,
    val streetAddress: String,
    val vendorData: String,
    @SerializedName("ISO2Code") val isO2Code: String
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
    val caseId: String,
    val status: String
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
