package com.example.veritypro_sdk.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** A screen-local collector, registered only while the capture screen is resumed. */
@Composable
internal fun rememberCaptureMotion(
    active: Boolean = true,
    onCollected: (CaptureRuntimeData) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val collector = remember(context) { MotionAnalysisCollector(context) }
    val callback = rememberUpdatedState(onCollected)
    val running = remember { booleanArrayOf(false) }
    val stop = remember(collector) {
        {
            if (running[0]) {
                running[0] = false
                callback.value(collector.stop().toRuntimeData())
            }
        }
    }
    DisposableEffect(owner, collector, active) {
        fun start() {
            if (active && !running[0]) {
                collector.start()
                running[0] = true
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> start()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        onDispose { owner.lifecycle.removeObserver(observer); stop() }
    }
    return stop
}
