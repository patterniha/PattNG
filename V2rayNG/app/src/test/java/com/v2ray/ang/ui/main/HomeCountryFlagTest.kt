package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HomeCountryFlagTest {

    @Test
    fun validIsoCountryCodesBecomeRegionalIndicatorFlags() {
        assertEquals("🇩🇪", countryFlagEmoji("DE"))
        assertEquals("🇮🇷", countryFlagEmoji("ir"))
        assertEquals("🇺🇸", countryFlagEmoji(" us "))
    }

    @Test
    fun missingOrNonIsoCountryValuesDoNotInventFlags() {
        assertEquals("", countryFlagEmoji(null))
        assertEquals("", countryFlagEmoji(""))
        assertEquals("", countryFlagEmoji("Germany"))
        assertEquals("", countryFlagEmoji("D3"))
        assertNull(normalizeCountryCode("Germany"))
    }
}
