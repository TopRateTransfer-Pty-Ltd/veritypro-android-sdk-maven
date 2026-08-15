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

    private val lock = Any()
    private val accelSamples = mutableListOf<FloatArray>()
    private val gyroSamples = mutableListOf<FloatArray>()
    private var startTimeMs = 0L
    @Volatile private var running = false

    fun start() {
        synchronized(lock) {
            if (running) return
            accelSamples.clear()
            gyroSamples.clear()
            startTimeMs = System.currentTimeMillis()
            running = true
        }

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

    /**
     * Instantaneous shake estimate: gyro magnitude averaged over the most
     * recent ~10 samples. Used by the shutter motion gate to fire the burst
     * at a still moment. Returns 0 when no gyro data is available (sensor
     * absent → the gate is a no-op, fail open).
     */
    fun recentGyroMagnitude(): Float {
        val snapshot = synchronized(lock) { gyroSamples.takeLast(10) }
        if (snapshot.isEmpty()) return 0f
        var sum = 0f
        for (s in snapshot) {
            sum += sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2])
        }
        return sum / snapshot.size
    }

    fun stop(): MotionResult {
        val (durationMs, accelSnapshot, gyroSnapshot) = synchronized(lock) {
            running = false
            sensorManager?.unregisterListener(this)
            Triple(
                System.currentTimeMillis() - startTimeMs,
                accelSamples.toList(),
                gyroSamples.toList()
            )
        }

        val accelStd = stdDev3(accelSnapshot)
        val gyroStd = stdDev3(gyroSnapshot)

        // Motion score: magnitude of combined accel + gyro std deviations, clamped [0, 1]
        val accelMag = sqrt(accelStd.map { it * it }.sum())
        val gyroMag = sqrt(gyroStd.map { it * it }.sum())
        val raw = (accelMag * 0.7f + gyroMag * 0.3f).coerceIn(0f, 1f)

        return MotionResult(
            durationMs = durationMs,
            sampleCount = accelSnapshot.size + gyroSnapshot.size,
            accelStdDev = accelStd,
            gyroStdDev = gyroStd,
            motionScore = raw,
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        val values = event.values.copyOf() // copy outside lock to minimise hold time
        synchronized(lock) {
            if (!running) return
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> accelSamples.add(values)
                Sensor.TYPE_GYROSCOPE -> gyroSamples.add(values)
            }
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
