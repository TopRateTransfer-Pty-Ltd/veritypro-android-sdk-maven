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
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val results = geocoder.getFromLocation(lat, lon, 1)
            if (!results.isNullOrEmpty()) {
                val a = results[0]
                val parts = listOfNotNull(
                    a.thoroughfare,
                    a.subLocality ?: a.locality,
                    a.adminArea,
                    a.countryName
                ).joinToString(", ")
                if (parts.isBlank()) null else parts
            } else null
        } catch (e: IOException) {
            null
        } catch (e: Exception) {
            null
        }
    }
}










//package com.example.veritypro_sdk.utils
//import android.location.Geocoder
//import java.util.Locale
//import android.Manifest
//import android.content.Context
//import android.content.pm.PackageManager
//import androidx.activity.compose.ManagedActivityResultLauncher
//import androidx.activity.compose.rememberLauncherForActivityResult
//import androidx.activity.result.contract.ActivityResultContracts
//import androidx.compose.runtime.Composable
//import androidx.core.content.ContextCompat
//
//import android.annotation.SuppressLint
//import com.google.android.gms.location.FusedLocationProviderClient
//import com.google.android.gms.location.LocationServices
//import kotlinx.coroutines.tasks.await
//import java.net.Inet4Address
//import java.net.NetworkInterface
//
//class LocationHelper(context: Context) {
//    private val fusedLocationClient: FusedLocationProviderClient =
//        LocationServices.getFusedLocationProviderClient(context)
//
//    @SuppressLint("MissingPermission") // we’ll check permission before calling
//    suspend fun getCurrentLocation(): android.location.Location? {
//        return fusedLocationClient.lastLocation.await()
//    }
//
//    fun getLocalIpAddress(): String? {
//        try {
//            val interfaces = NetworkInterface.getNetworkInterfaces()
//            for (intf in interfaces) {
//                val addrs = intf.inetAddresses
//                for (addr in addrs) {
//                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
//                        val ip = addr.hostAddress
//                        if (!ip.isNullOrBlank()) return ip
//                    }
//                }
//            }
//        } catch (e: Exception) {
//            e.printStackTrace()
//        }
//        return null
//    }
//    fun reverseGeocode(context: Context, latitude: Double, longitude: Double): String? {
//        return try {
//            val geocoder = Geocoder(context, Locale.getDefault())
//            val list = geocoder.getFromLocation(latitude, longitude, 1)
//            if (!list.isNullOrEmpty()) {
//                val a = list[0]
//                "${a.locality ?: a.subAdminArea ?: ""}, ${a.countryName ?: ""}".trim { it <= ' ' }
//            } else null
//        } catch (e: Exception) {
//            e.printStackTrace()
//            null
//        }
//    }
//}
//
//object LocationUtils {
//    fun hasLocationPermissions(context: Context): Boolean {
//        val fineLocation = ContextCompat.checkSelfPermission(
//            context, Manifest.permission.ACCESS_FINE_LOCATION
//        ) == PackageManager.PERMISSION_GRANTED
//
//        val coarseLocation = ContextCompat.checkSelfPermission(
//            context, Manifest.permission.ACCESS_COARSE_LOCATION
//        ) == PackageManager.PERMISSION_GRANTED
//
//        return fineLocation || coarseLocation
//    }
//
//    @Composable
//    fun createLocationLauncher(
//        onResult: (Boolean) -> Unit
//    ): ManagedActivityResultLauncher<Array<String>, Map<String, @JvmSuppressWildcards Boolean>> {
//        return rememberLauncherForActivityResult(
//            contract = ActivityResultContracts.RequestMultiplePermissions(),
//            onResult = { permissions ->
//                onResult(
//                    permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true &&
//                            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
//                )
//            }
//        )
//    }
//
//}