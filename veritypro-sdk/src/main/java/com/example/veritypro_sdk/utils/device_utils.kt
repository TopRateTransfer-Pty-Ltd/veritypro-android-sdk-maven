package com.example.veritypro_sdk.utils

object DeviceUtils {
    fun getDevicePlatform(): String {
        val manufacturer = android.os.Build.MANUFACTURER
        val model = android.os.Build.MODEL
        val deviceName = if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
        return deviceName;

    }
}