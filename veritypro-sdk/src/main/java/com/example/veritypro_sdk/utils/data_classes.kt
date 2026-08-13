package com.example.veritypro_sdk.utils

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class LivenessResult(
    val success: Boolean,
    val sessionToken: String? = null,
    val confidence: Float? = null,
    val error: String? = null,
    val completedModules: List<String>? = null,
    val addressSessionId: String? = null,
    val eddCaseId: String? = null,
    val sessionId: String? = null,
    /** vpds_* token minted by VpDeviceSessionService. Null when collection failed or timed out. */
    val deviceToken: String? = null
) : Parcelable