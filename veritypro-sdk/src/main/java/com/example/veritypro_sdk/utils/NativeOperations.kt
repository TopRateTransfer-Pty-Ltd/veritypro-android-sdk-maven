package com.example.veritypro_sdk.utils

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap

/** Process-local cancellation; no exported broadcast or host workflow implementation. */
internal object NativeOperations {
    private class Operation(var cancelled: Boolean = false, var callback: (() -> Unit)? = null)
    private val operations = ConcurrentHashMap<String, Operation>()
    fun prepare(id: String) { require(operations.putIfAbsent(id, Operation()) == null) { "Operation already active" } }
    fun register(id: String, callback: () -> Unit) {
        val entry = operations.getOrPut(id) { Operation() }
        val cancelled = synchronized(entry) { entry.callback = callback; entry.cancelled }
        if (cancelled) Handler(Looper.getMainLooper()).post(callback)
    }
    fun cancel(id: String): Boolean {
        val entry = operations[id] ?: return false
        val callback = synchronized(entry) { entry.cancelled = true; entry.callback }
        callback?.let { Handler(Looper.getMainLooper()).post(it) }
        return true
    }
    fun unregister(id: String) { operations.remove(id) }
}
