package com.example.veritypro_sdk.utils

import java.util.TimeZone
import android.content.Context
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException

internal fun timezoneOffsetMinutes(zone: TimeZone, nowMillis: Long): Int = -zone.getOffset(nowMillis) / 60000

internal fun MotionAnalysisCollector.MotionResult.toRuntimeData(): CaptureRuntimeData {
    if (sampleCount < 2) return CaptureRuntimeData(motionAvailability = "unavailable")
    return CaptureRuntimeData(
        motionDurationMs = durationMs, motionSampleCount = sampleCount,
        accelStdDev = accelStdDev.takeIf { accelerometerSampleCount >= 2 },
        gyroStdDev = gyroStdDev.takeIf { gyroscopeSampleCount >= 2 },
        motionScore = motionScore.takeIf { accelerometerSampleCount >= 2 && gyroscopeSampleCount >= 2 },
        motionAvailability = "collected",
    )
}

/** Optional existing location evidence: bounded lookup, no permission prompt or fabricated coordinates. */
internal suspend fun CaptureRuntimeData.withCurrentLocation(context: Context): CaptureRuntimeData {
    val fresh = copy(latitude = null, longitude = null, locationAccuracy = null, locationString = null,
        locationTimestamp = null, locationSource = null, countryCode = null)
    if (!LocationHelper.hasLocationPermissions(context)) return fresh.copy(locationAvailability = "permission_denied")
    return try {
        val location = withTimeoutOrNull(3_000) { LocationHelper(context).getCurrentLocation() }
        if (location == null) fresh.copy(locationAvailability = "unavailable") else fresh.copy(
            latitude = location.latitude, longitude = location.longitude, locationAccuracy = location.accuracy,
            locationTimestamp = java.time.Instant.ofEpochMilli(location.time).toString(),
            locationSource = location.provider, locationAvailability = "collected",
        )
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { fresh.copy(locationAvailability = "error") }
}
