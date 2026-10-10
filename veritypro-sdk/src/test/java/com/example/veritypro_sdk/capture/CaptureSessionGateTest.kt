package com.example.veritypro_sdk.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSessionGateTest {
    @Test
    fun `a result from an open camera session is current`() {
        val gate = CaptureSessionGate(deleteFile = {})
        val token = gate.open()
        assertTrue(gate.isCurrent(token))
    }

    @Test
    fun `closing the camera while the photo is processing invalidates the result`() {
        val gate = CaptureSessionGate(deleteFile = {})
        val token = gate.open()
        gate.close(token) // user pressed X while the still was saving / being assembled
        assertFalse(gate.isCurrent(token))
    }

    @Test
    fun `a result from a closed session stays invalid after the camera reopens`() {
        val gate = CaptureSessionGate(deleteFile = {})
        val old = gate.open()
        gate.close(old)
        val fresh = gate.open()
        assertFalse(gate.isCurrent(old))
        assertTrue(gate.isCurrent(fresh))
    }

    @Test
    fun `a late close of an old session does not invalidate the newer one`() {
        val gate = CaptureSessionGate(deleteFile = {})
        val old = gate.open()
        val fresh = gate.open() // retake: the new camera composes before the old one is disposed
        gate.close(old)
        assertFalse(gate.isCurrent(old))
        assertTrue(gate.isCurrent(fresh))
    }

    @Test
    fun `discarding a result deletes its still and clip`() {
        val deleted = mutableListOf<String>()
        val gate = CaptureSessionGate(deleteFile = { deleted += it })
        val raw = RawSideCapture(
            tag = CaptureTag("a", CaptureSide.FRONT, 0), stillPath = "s.jpg", shutterAtMs = 1, stillSavedAtMs = 2,
            recording = RecordingTimeline(VideoBinding.BOUND, filePath = "c.mp4"),
        )
        gate.discard(raw)
        assertEquals(listOf("s.jpg", "c.mp4"), deleted)
    }
}
