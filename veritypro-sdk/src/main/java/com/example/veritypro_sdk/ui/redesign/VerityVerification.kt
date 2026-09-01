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
import com.example.veritypro_sdk.utils.VerityOption

/**
 * Public entry point for the redesigned verification flow (B1). Wraps the theme, emits
 * `session_started`, and hosts the state-driven flow.
 *
 * Pass [options] to activate the full live pipeline (KYC session init, real camera, AWS liveness,
 * document upload). When [options] is null the flow runs in demo/stub mode — no API calls made.
 *
 * Integrators host this from a screen/Activity and receive the terminal [VerityFlowState].
 */
@Composable
fun VerityVerification(
    documentOptions: List<VerityDocOption>,
    options: VerityOption? = null,
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
            options = options,
            onFinished = onResult,
            analytics = analytics
        )
    }
}
