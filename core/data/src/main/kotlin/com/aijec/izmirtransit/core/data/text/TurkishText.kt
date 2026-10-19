package com.aijec.izmirtransit.core.data.text

import java.util.Locale

object TurkishText {
    private val TURKISH = Locale.forLanguageTag("tr")

    /**
     * Folds text for search: Turkish-aware lowercase, diacritics removed, punctuation
     * turned into single spaces. "İZMİR", "Izmir" and "izmir" all fold to "izmir";
     * "EVKA.5" folds to "evka 5". Apply to both the stored text and the query.
     */
    fun fold(text: String): String {
        val lower = text.lowercase(TURKISH)
        val out = StringBuilder(lower.length)
        var pendingSpace = false
        for (c in lower) {
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
            val mapped = when (c) {
                'ı', 'î', 'ì', 'í' -> 'i'
                'ş' -> 's'
                'ğ' -> 'g'
                'ç' -> 'c'
                'ö' -> 'o'
                'ü', 'û', 'ù', 'ú' -> 'u'
                'â', 'à', 'á' -> 'a'
                'é', 'è', 'ê' -> 'e'
                else -> c
            }
            if (mapped.isLetterOrDigit()) {
                if (pendingSpace && out.isNotEmpty()) out.append(' ')
                pendingSpace = false
                out.append(mapped)
            } else {
                pendingSpace = true
            }
        }
        return out.toString()
    }
}
