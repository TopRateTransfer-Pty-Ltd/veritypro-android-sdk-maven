package com.example.veritypro_sdk.ui

import com.example.veritypro_sdk.ui.prototype.parseDeclaredIncome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Declared income feeds the AML source-of-funds field: a wrong-but-valid number is worse than none. */
class ParseDeclaredIncomeTest {
    private fun p(s: String) = parseDeclaredIncome(s)

    @Test fun plain() = assertEquals(4500.0, p("4500")!!, 0.0)
    @Test fun dotDecimal() = assertEquals(4500.5, p("4500.50")!!, 0.0)
    @Test fun commaDecimal() = assertEquals(1.5, p("1,5")!!, 0.0)
    @Test fun commaThousands() = assertEquals(1000.0, p("1,000")!!, 0.0)
    @Test fun usMixed() = assertEquals(1000.5, p("1,000.50")!!, 0.0)
    @Test fun europeanMixed() = assertEquals(1000.5, p("1.000,50")!!, 0.0)
    @Test fun europeanMixedLarge() = assertEquals(1234567.89, p("1.234.567,89")!!, 0.001)
    @Test fun usMixedLarge() = assertEquals(1234567.89, p("1,234,567.89")!!, 0.001)
    @Test fun blankIsNull() = assertNull(p("  "))
    @Test fun textIsNull() = assertNull(p("abc"))
}
