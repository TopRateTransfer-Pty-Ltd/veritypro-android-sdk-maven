package com.example.veritypro_sdk.ui.prototype

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Owner 2026-10-08 option a: when the integrator's server already started the Basic EDD
 * assessment (trigger-edd), the SDK skips the income screen and goes straight to the document
 * upload for that assessment. Without it, the SDK collects income and creates the assessment.
 */
class ServerEddAssessmentStageTest {
    @Test
    fun eddWithServerAssessment_startsAtUpload() =
        assertEquals(ProtoStage.EddUpload, stageForModule("EDD", "6b9a1f3e-0000-4000-8000-000000000001"))

    @Test
    fun eddWithoutServerAssessment_startsAtIncome() {
        assertEquals(ProtoStage.EddIncome, stageForModule("EDD", null))
        assertEquals(ProtoStage.EddIncome, stageForModule("EDD", "  "))
    }

    @Test
    fun otherModulesIgnoreTheAssessmentId() {
        assertEquals(ProtoStage.SelfieIntro, stageForModule("BIOMETRIC", "x"))
        assertEquals(ProtoStage.ChooseId, stageForModule("DOCUMENT", "x"))
    }
}
