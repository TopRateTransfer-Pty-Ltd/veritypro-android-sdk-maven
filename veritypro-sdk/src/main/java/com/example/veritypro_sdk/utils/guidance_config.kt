package com.example.veritypro_sdk.utils

import androidx.compose.ui.graphics.Color

/**
 * Design tokens and configuration for the camera guidance system.
 * Shared across overlay, distance meter, onboarding, and auto-capture components.
 */
object GuidanceConfig {
    // State colors
    val STATE_IDLE = Color(0xFF9E9E9E)
    val STATE_AMBER = Color(0xFFFF9800)
    val STATE_GREEN = Color(0xFF4CAF50)
    val STATE_ERROR = Color(0xFFF44336)
    val PIN_GLOW_GREEN = Color(0x664CAF50)
    val PIN_GLOW_AMBER = Color(0x66FF9800)
    val OVERLAY_DARK = Color(0xBF000000.toInt())
    val BAR_TRACK = Color(0x33FFFFFF)
    val SHEET_BG = Color(0xE6111111.toInt())

    // Corner pins
    const val PIN_LENGTH = 22f      // dp
    const val PIN_THICKNESS = 3f    // dp
    const val PIN_RADIUS = 4f       // dp
    const val SHEET_RADIUS = 20f    // dp

    // Auto-zoom
    const val ZOOM_MIN = 1.0f
    const val ZOOM_MAX = 2.0f
    const val ZOOM_STEP = 0.1f
    const val ZOOM_INTERVAL_MS = 200L
    const val OPTIMAL_COVERAGE_MIN = 0.55f
    const val OPTIMAL_COVERAGE_MAX = 0.80f
    const val OPTIMAL_COVERAGE_TARGET = 0.70f

    // Auto-capture
    const val COUNTDOWN_DURATION_MS = 2000L

    // Onboarding
    const val PREF_KEY_ONBOARDING = "kyc_onboard_seen"
    const val ONBOARDING_SKIP_DELAY_MS = 1500L
    const val ONBOARDING_TIP_AUTO_MS = 3500L

    // Animation durations
    const val PIN_COLOR_CHANGE_MS = 400
    const val PIN_PULSE_MS = 1200
    const val PIN_GLOW_APPEAR_MS = 300
    const val BAR_FILL_MS = 300
    const val SHEET_EXPAND_MS = 350
    const val THUMBNAIL_HERO_MS = 400

    // Pre-shutter quality gates (P0 first-attempt-pass, 2026-08-15)
    // Preview-sharpness lock gate: 4-neighbour Laplacian variance at 200px.
    // Permissive on purpose — catches definite mush only, until funnel data
    // calibrates a tighter line per device tier.
    const val PREVIEW_SHARPNESS_MIN = 20.0

    // Auto-torch when scene median luma is genuinely dark (0 excluded — a
    // black wedged preview must trigger the watchdog, not the torch).
    const val AUTO_TORCH_LUMA_BELOW = 45

    // Motion gate: defer the shutter until gyro magnitude settles, bounded.
    const val MOTION_GATE_GYRO_MAX = 0.25f
    const val MOTION_GATE_MAX_WAIT_MS = 900L
}
