package com.example.veritypro_sdk.utils

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class VerityOption(
    val apiKey: String,
    val integrationId: String,
    val firstName: String,
    val lastName: String,
    val dateOfBirth: String,
    val vendorData: String,
    val isO2Code: String,
    val streetAddress: String? = null,
    val requiredModules: List<String>? = null,
) : Parcelable


data class DataPayload(
    val integrationId: String,
    val firstName: String,
    val lastName: String,
    val dateOfBirth: String,
    val vendorData: String,
    val isO2Code: String,
    val streetAddress: String? = null,
    val requiredModules: List<String>? = null,
)

fun VerityOption.toPayload(): DataPayload {
    return DataPayload(
        integrationId = this.integrationId,
        firstName = this.firstName,
        lastName = this.lastName,
        dateOfBirth = this.dateOfBirth,
        vendorData = this.vendorData,
        isO2Code = this.isO2Code,
        streetAddress = this.streetAddress,
        requiredModules = this.requiredModules,
    )
}
