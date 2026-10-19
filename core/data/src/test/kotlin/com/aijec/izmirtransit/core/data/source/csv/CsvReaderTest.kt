package com.aijec.izmirtransit.core.data.source.csv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CsvReaderTest {
    private fun readAll(text: String, delimiter: Char = ';'): List<List<String>> {
        val reader = CsvReader(text.reader(), delimiter)
        return generateSequence { reader.readRecord() }.toList()
    }

    @Test
    fun `handles BOM, CRLF, quoted delimiters, escaped quotes and blank lines`() {
        val text = "﻿a;b;c\r\n1;\"x;y\";\"he said \"\"hi\"\"\"\r\n\r\n2;\"multi\nline\";\r\n"
        assertEquals(
            listOf(
                listOf("a", "b", "c"),
                listOf("1", "x;y", "he said \"hi\""),
                listOf("2", "multi\nline", ""),
            ),
            readAll(text),
        )
    }

    @Test
    fun `field starting with an escaped quote, as in line 798's notes`() {
        val rows = readAll("798;\"\"\"Hafta içi günlerde; Tire'den\";x\r\n")
        assertEquals(listOf("798", "\"Hafta içi günlerde; Tire'den", "x"), rows.single())
    }

    @Test
    fun `quote inside an unquoted field is literal`() {
        assertEquals(listOf("a 5\" b", "c"), readAll("a 5\" b;c").single())
    }

    @Test
    fun `last line without newline and empty input`() {
        assertEquals(listOf(listOf("1", "2")), readAll("1,2", ','))
        assertNull(CsvReader("".reader(), ';').readRecord())
    }

    @Test
    fun `rows are addressed by header name and missing columns read empty`() {
        val rows = mutableListOf<CsvRow>()
        "HAT_NO;YON;BOYLAM;ENLEM\r\n5;1;26.99;38.3921\r\n6;2\r\n".reader().forEachCsvRow(';') { rows += it }
        assertEquals("38.3921", rows[0]["ENLEM"])
        assertEquals("26.99", rows[0]["BOYLAM"])
        assertEquals("", rows[1]["ENLEM"])
        assertEquals("", rows[1]["NOT_A_COLUMN"])
        assertEquals(3, rows[1].lineNumber)
    }
}
