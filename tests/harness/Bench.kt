package org.klippelgo.tests

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import java.io.File
import java.time.Instant

/**
 * Measurements a run took, each with the most it may be. They are printed as they are taken and kept in
 * `tests/.run/report/performance.json`, one entry per measurement with every run's value, so a slow drift
 * shows before it breaks a budget.
 *
 * The budgets are for an emulator on a developer's computer, with a chain that mines a block every two
 * seconds. They are there to catch something getting several times slower, not to certify a speed.
 */
object Bench {
    private val json = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    /** Runs `block` and gives how long it took, in milliseconds. */
    fun time(block: () -> Any?): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    fun median(values: List<Long>): Long = values.sorted()[values.size / 2]

    /** Keeps a measurement and fails if it is over its budget. */
    @Synchronized
    fun record(name: String, value: Long, unit: String, budget: Long) {
        println("  %-58s %8d %-3s (budget %d)".format(name, value, unit, budget))
        val file = File(Stack.current.reportDir, "performance.json")
        @Suppress("UNCHECKED_CAST")
        val report = (if (file.isFile) runCatching { json.readValue(file, LinkedHashMap::class.java) }.getOrNull() else null) as LinkedHashMap<String, Any>?
            ?: LinkedHashMap()
        @Suppress("UNCHECKED_CAST")
        val entry = (report[name] as? MutableMap<String, Any>) ?: LinkedHashMap()
        @Suppress("UNCHECKED_CAST")
        val runs = (entry["runs"] as? MutableList<Any>) ?: mutableListOf()
        runs += mapOf("at" to Instant.now().toString(), "value" to value)
        entry["unit"] = unit
        entry["budget"] = budget
        entry["last"] = value
        entry["runs"] = runs.takeLast(50)
        report[name] = entry
        file.writeText(json.writeValueAsString(report))
        if (value > budget) throw AssertionError("$name took $value $unit, over its budget of $budget $unit")
    }
}
