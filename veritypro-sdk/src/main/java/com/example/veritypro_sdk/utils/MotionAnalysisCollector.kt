package com.example.veritypro_sdk.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt

/**
 * Collects accelerometer and gyroscope samples during document/selfie capture
 * to detect tripod/mount usage (static device = potential spoofing).
 *
 * Usage:
 *   val collector = MotionAnalysisCollector(context)
 *   collector.start()
 *   // ... capture happens ...
 *   val result = collector.stop()
 *   // result feeds into CaptureRuntimeData.motionDurationMs, motionSampleCount, etc.
 */
class MotionAnalysisCollector(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val accelSamples = mutableListOf<FloatArray>()
    private val gyroSamples = mutableListOf<FloatArray>()
    private var startTimeMs = 0L
    private var running = false

    fun start() {
        if (running) return
        accelSamples.clear()
        gyroSamples.clear()
        startTimeMs = System.currentTimeMillis()
        running = true

        accelSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        gyroSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    data class MotionResult(
        val durationMs: Long,
        val sampleCount: Int,
        val accelStdDev: FloatArray,
        val gyroStdDev: FloatArray,
        val motionScore: Float,
    )

    fun stop(): MotionResult {
        running = false
        sensorManager?.unregisterListener(this)
        val durationMs = System.currentTimeMillis() - startTimeMs

        val accelStd = stdDev3(accelSamples)
        val gyroStd = stdDev3(gyroSamples)

        // Motion score: magnitude of combined accel + gyro std deviations, clamped [0, 1]
        val accelMag = sqrt(accelStd.map { it * it }.sum())
        val gyroMag = sqrt(gyroStd.map { it * it }.sum())
        val raw = (accelMag * 0.7f + gyroMag * 0.3f).coerceIn(0f, 1f)

        return MotionResult(
            durationMs = durationMs,
            sampleCount = accelSamples.size + gyroSamples.size,
            accelStdDev = accelStd,
            gyroStdDev = gyroStd,
            motionScore = raw,
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> accelSamples.add(event.values.copyOf())
            Sensor.TYPE_GYROSCOPE -> gyroSamples.add(event.values.copyOf())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun stdDev3(samples: List<FloatArray>): FloatArray {
        if (samples.size < 2) return floatArrayOf(0f, 0f, 0f)
        val n = samples.size.toFloat()
        val means = FloatArray(3)
        samples.forEach { s -> for (i in 0..2) means[i] += s[i] }
        for (i in 0..2) means[i] /= n

        val variance = FloatArray(3)
        samples.forEach { s -> for (i in 0..2) { val d = s[i] - means[i]; variance[i] += d * d } }
        return FloatArray(3) { sqrt(variance[it] / n) }
    }
}
