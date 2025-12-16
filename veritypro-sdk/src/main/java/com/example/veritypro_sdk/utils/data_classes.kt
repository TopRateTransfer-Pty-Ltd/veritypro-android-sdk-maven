package com.example.veritypro_sdk.utils

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class LivenessResult(
    val success: Boolean,
    val sessionToken: String? = null,
    val confidence: Float? = null,
    val error: String? = null
) : Parcelable