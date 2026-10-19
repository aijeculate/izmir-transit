package com.aijec.izmirtransit.core.data.text

import kotlin.test.Test
import kotlin.test.assertEquals

class TurkishTextTest {
    @Test
    fun `dotted and dotless i all fold to i`() {
        assertEquals("izmir", TurkishText.fold("İZMİR"))
        assertEquals("izmir", TurkishText.fold("IZMIR"))
        assertEquals("izmir", TurkishText.fold("ızmir"))
        assertEquals("izmir", TurkishText.fold("izmir"))
    }

    @Test
    fun `diacritics are removed`() {
        assertEquals("uckuyular iskele", TurkishText.fold("ÜÇKUYULAR İSKELE"))
        assertEquals("sirinyer aktarma", TurkishText.fold("Şirinyer Aktarma"))
        assertEquals("dogalgaz", TurkishText.fold("DOĞALGAZ"))
        assertEquals("gol", TurkishText.fold("Göl"))
    }

    @Test
    fun `punctuation collapses to single spaces`() {
        assertEquals("evka 5 egekent akt mrk", TurkishText.fold("EVKA.5 - EGEKENT AKT.MRK"))
        assertEquals("f altay", TurkishText.fold("  F.ALTAY  "))
        assertEquals("", TurkishText.fold(" - . "))
    }

    @Test
    fun `decomposed dotted capital I folds the same as the composed one`() {
        assertEquals("izmir", TurkishText.fold("İZMİR"))
    }
}
