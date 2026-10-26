package com.example.veritypro_sdk.ui.prototype

import org.junit.Assert.assertEquals
import org.junit.Test

class DocCueTextTest {
    @Test
    fun `flip names the card the customer chose`() {
        assertEquals("Now turn your licence over", docCueText(DocCueKind.FLIP, "Driver's Licence").first)
        assertEquals("Now turn your licence over", docCueText(DocCueKind.FLIP, "Driver License").first)
        assertEquals("Now turn your ID card over", docCueText(DocCueKind.FLIP, "National ID Card").first)
        assertEquals("Take a photo of the back.", docCueText(DocCueKind.FLIP, null).second)
    }

    @Test
    fun `passport cue asks for the photo page`() {
        assertEquals("Open your passport to the photo page", docCueText(DocCueKind.PASSPORT, "Passport").first)
    }

    @Test
    fun `card art follows the document label`() {
        assertEquals(CueCardKind.LICENCE, cueCardKind("driver licence"))
        assertEquals(CueCardKind.ID, cueCardKind("Identity card"))
        assertEquals(CueCardKind.ID, cueCardKind(null))
    }

    @Test
    fun `camera title counts sides only for two-sided documents`() {
        assertEquals("DRIVER'S LICENCE · FRONT · 1 OF 2", captureSideTitle("Driver's Licence", "Front", 1, 2))
        assertEquals("DRIVER'S LICENCE · BACK · 2 OF 2", captureSideTitle("Driver's Licence", "Back", 2, 2))
        assertEquals("PASSPORT · FRONT", captureSideTitle("Passport", "Front", 1, 1))
    }

    @Test
    fun `camera bar title may only wrap after the document name`() {
        val nb = ' '
        assertEquals("DRIVER'S${nb}LICENCE$nb· FRONT$nb·${nb}1${nb}OF${nb}2",
            captureBarTitle("DRIVER'S LICENCE · FRONT · 1 OF 2"))
        assertEquals("PASSPORT$nb· FRONT", captureBarTitle("PASSPORT · FRONT"))
        assertEquals("NO${nb}SEPARATOR", captureBarTitle("NO SEPARATOR"))
    }
}
