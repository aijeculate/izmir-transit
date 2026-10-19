package com.aijec.izmirtransit.core.data.source

/** Collects skipped or suspicious records during an import: a count per kind plus a few samples. */
class ImportIssues(private val samplesPerKind: Int = 3) {
    private val counts = linkedMapOf<String, Int>()
    private val samples = mutableMapOf<String, MutableList<String>>()

    fun add(kind: String, sample: Any?) {
        counts[kind] = (counts[kind] ?: 0) + 1
        val list = samples.getOrPut(kind) { mutableListOf() }
        if (list.size < samplesPerKind) list += sample.toString().take(200)
    }

    fun count(kind: String): Int = counts[kind] ?: 0

    val isEmpty: Boolean get() = counts.isEmpty()

    fun summary(): List<String> = counts.map { (kind, count) ->
        "$kind: $count (e.g. ${samples[kind].orEmpty().joinToString(" | ")})"
    }
}
