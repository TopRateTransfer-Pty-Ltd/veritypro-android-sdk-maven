package com.example.veritypro_sdk.utils

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import kotlin.coroutines.resume

class LocationHelper(private val context: Context) {

    companion object {
        fun hasLocationPermissions(context: Context): Boolean {
            val fine = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            return fine || coarse
        }
    }

    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(context) }

    suspend fun getCurrentLocation(): Location? = suspendCancellableCoroutine { cont ->
        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            if (!cont.isCompleted) cont.resume(null)
            return@suspendCancellableCoroutine
        }

        try {
            val cts = CancellationTokenSource()
            val task = fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            task.addOnSuccessListener { location ->
                if (!cont.isCompleted) cont.resume(location)
            }.addOnFailureListener {
                if (!cont.isCompleted) cont.resume(null)
            }.addOnCanceledListener {
                if (!cont.isCompleted) cont.resume(null)
            }
            cont.invokeOnCancellation { cts.cancel() }
        } catch (se: SecurityException) {
            se.printStackTrace()
            if (!cont.isCompleted) cont.resume(null)
        } catch (e: Exception) {
            e.printStackTrace()
            if (!cont.isCompleted) cont.resume(null)
        }
    }

    /**
     * Returns the device's local/private network interface IP address (e.g. 192.168.x.x, 10.x.x.x).
     *
     * NOTE: This does NOT return the public/external IP address visible to remote servers.
     * To obtain the public IP, a call to an external service (e.g. https://api.ipify.org)
     * would be required. The server-side can also capture the public IP from the incoming
     * request headers, which is the recommended approach.
     *
     * The returned value is sent as the "IpAddress" multipart field to the backend,
     * which represents the device's local network interface address.
     */
    fun getLocalIpAddress(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress
                        if (ip.isNotBlank()) return ip
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    fun reverseGeocode(context: Context, lat: Double, lon: Double): String? {
        return reverseGeocodeWithCountry(context, lat, lon)?.first
    }

    /** Returns (locationString, countryCode) pair, or null if geocoding fails. */
    fun reverseGeocodeWithCountry(context: Context, lat: Double, lon: Double): Pair<String, String?>? {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val results = geocoder.getFromLocation(lat, lon, 1)
            if (!results.isNullOrEmpty()) {
                val a = results[0]
                val parts = listOfNotNull(
                    a.thoroughfare,
                    a.subLocality ?: a.locality,
                    a.adminArea,
                    a.countryName
                ).joinToString(", ")
                if (parts.isBlank()) null else Pair(parts, a.countryCode?.uppercase())
            } else null
        } catch (e: IOException) {
            null
        } catch (e: Exception) {
            null
        }
    }
}