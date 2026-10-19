package com.aijec.izmirtransit.core.data.source.csv

import java.io.BufferedReader
import java.io.Reader

/**
 * Streaming RFC 4180 reader. ESHOT files use `;`, GTFS uses `,`. Handles quoted fields
 * containing the delimiter, doubled `""` escapes, line breaks inside quotes, CRLF and a
 * leading BOM. A quote in the middle of an unquoted field is kept as a literal character.
 */
class CsvReader(reader: Reader, private val delimiter: Char) {
    private val input: BufferedReader = reader as? BufferedReader ?: BufferedReader(reader, 1 shl 16)
    private var peeked = -2
    private var first = true

    private fun read(): Int {
        if (peeked != -2) return peeked.also { peeked = -2 }
        return input.read()
    }

    private fun peek(): Int {
        if (peeked == -2) peeked = input.read()
        return peeked
    }

    /** Next record, or null at end of input. Blank lines are skipped. */
    fun readRecord(): List<String>? {
        if (first) {
            first = false
            if (peek() == 0xFEFF) read()
        }
        while (true) {
            if (peek() == -1) return null
            val record = readOne()
            if (record.size == 1 && record[0].isEmpty()) continue
            return record
        }
    }

    private fun readOne(): List<String> {
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var atFieldStart = true
        var quoted = false
        while (true) {
            val c = read()
            if (c == -1) {
                fields += field.toString()
                return fields
            }
            val ch = c.toChar()
            if (quoted) {
                if (ch == '"') {
                    if (peek() == '"'.code) {
                        read()
                        field.append('"')
                    } else {
                        quoted = false
                    }
                } else {
                    field.append(ch)
                }
                continue
            }
            when {
                ch == '"' && atFieldStart -> {
                    quoted = true
                    atFieldStart = false
                }
                ch == delimiter -> {
                    fields += field.toString()
                    field.setLength(0)
                    atFieldStart = true
                }
                ch == '\r' -> {
                    if (peek() == '\n'.code) read()
                    fields += field.toString()
                    return fields
                }
                ch == '\n' -> {
                    fields += field.toString()
                    return fields
                }
                else -> {
                    field.append(ch)
                    atFieldStart = false
                }
            }
        }
    }
}

/** A data row addressed by header name. Missing columns read as "". */
class CsvRow(private val header: Map<String, Int>, private val values: List<String>, val lineNumber: Int) {
    operator fun get(column: String): String = header[column]?.let { values.getOrNull(it) }?.trim().orEmpty()

    override fun toString(): String = "line $lineNumber: ${values.joinToString(";")}"
}

/** Reads a header row and then calls [action] for each data row. Closes the reader. */
fun Reader.forEachCsvRow(delimiter: Char, action: (CsvRow) -> Unit) {
    use {
        val csv = CsvReader(this, delimiter)
        val header = csv.readRecord()?.mapIndexed { i, name -> name.trim() to i }?.toMap() ?: return
        var line = 1
        while (true) {
            val record = csv.readRecord() ?: break
            line++
            action(CsvRow(header, record, line))
        }
    }
}
