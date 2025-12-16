package com.example.veritypro_sdk.utils

import android.os.Parcelable
import com.example.veritypro_sdk.ui.theme.ThemeMode
import kotlinx.parcelize.Parcelize

@Parcelize
data class VerityOption(
    val apiKey: String,
    val integrationId: String,
    val firstName: String,
    val lastName: String,
    val vendorData: String,
    val isO2Code: String,
) : Parcelable


data class DataPayload(
    val integrationId: String,
    val firstName: String,
    val lastName: String,
    val vendorData: String,
    val isO2Code: String
)

fun VerityOption.toPayload(): DataPayload {
    return DataPayload(
        integrationId = this.integrationId,
        firstName = this.firstName,
        lastName = this.lastName,
        vendorData = this.vendorData,
        isO2Code = this.isO2Code
    )
}
