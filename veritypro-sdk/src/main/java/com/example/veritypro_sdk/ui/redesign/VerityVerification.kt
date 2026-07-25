package com.example.veritypro_sdk.ui.redesign

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.example.veritypro_sdk.ui.redesign.analytics.LogcatVerityAnalytics
import com.example.veritypro_sdk.ui.redesign.analytics.VerityAnalytics
import com.example.veritypro_sdk.ui.redesign.analytics.VerityAnalyticsEvent
import com.example.veritypro_sdk.ui.redesign.screens.VerityDocOption
import com.example.veritypro_sdk.ui.redesign.state.VerityFlowState
import com.example.veritypro_sdk.ui.theme.ThemeMode
import com.example.veritypro_sdk.ui.theme.VerityProTheme

/**
 * Public entry point for the redesigned verification flow (B1). Wraps the theme (auto light/dark
 * via [themeMode]), emits `session_started`, provides analytics, and hosts the state-driven flow.
 * An integrator hosts this from a screen/Activity and receives the terminal [VerityFlowState].
 *
 * This coexists with the legacy SDK entry (converge-not-rebuild); the legacy flow is untouched.
 */
@Composable
fun VerityVerification(
    documentOptions: List<VerityDocOption>,
    onResult: (VerityFlowState) -> Unit,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    analytics: VerityAnalytics = LogcatVerityAnalytics()
) {
    LaunchedEffect(Unit) {
        analytics.track(VerityAnalyticsEvent.sessionStarted("combined"))
    }
    VerityProTheme(mode = themeMode, dynamicColor = false) {
        VerityFlowHost(
            documentOptions = documentOptions,
            onFinished = onResult,
            analytics = analytics
        )
    }
}
