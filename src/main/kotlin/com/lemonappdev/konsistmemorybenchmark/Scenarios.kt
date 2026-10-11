package com.lemonappdev.konsistmemorybenchmark

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.provider.KoChildProvider
import com.lemonappdev.konsist.api.provider.KoParentProvider
import com.lemonappdev.konsist.api.verify.assertTrue
import java.lang.management.ManagementFactory
import java.lang.management.ThreadMXBean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Runs queries that were slow in some Konsist versions (e.g. exponential `parents(indirectParents = true)` or
 * `children()` checking parents of every project declaration on every call) and prints the time of every scenario:
 * `SCENARIO <name> items=<processed>/<total> result=<sum of results> t=<seconds> cpu=<seconds>`. Queries run in a
 * separate thread, so CPU time of the scenario is printed here (not in the run metrics of the main thread).
 *
 * Every scenario stops when it exceeds the time budget (marked `budget exceeded`), so slow versions can be compared
 * by the number of processed items. A single query can take hours in a slow version and it can't be cancelled, so
 * the remaining scenarios are skipped when a query doesn't finish within 5 seconds after the budget is exceeded (the
 * query would slow them down).
 * Scenarios run from the fastest to the potentially slowest and share caches (e.g. the first scenario resolving types
 * pays for parsing the files where types are declared).
 */
class Scenarios(
    private val budgetSeconds: Int,
) {
    fun run() {
        if (queryStillRunning) {
            println("SCENARIOS skipped: a query of the previous run is still running")
            return
        }

        val scope = Konsist.scopeFromProject()
        val types = scope.classesAndInterfacesAndObjects(includeNested = true, includeLocal = false)
        val parentProviders = types.filterIsInstance<KoParentProvider>()
        val childProviders = types.filterIsInstance<KoChildProvider>()
        val functions = scope.functions(includeNested = true, includeLocal = false)

        scenario("parents().sourceDeclaration", parentProviders) { type -> type.parents().count { it.sourceDeclaration != null } }
        scenario("parameter and return type sourceDeclaration", functions) { function ->
            function.parameters.count { it.type.sourceDeclaration != null } + if (function.returnType?.sourceDeclaration != null) 1 else 0
        }
        scenario("property type sourceDeclaration", scope.properties(includeNested = true)) { if (it.type?.sourceDeclaration != null) 1 else 0 }
        scenario("import sourceDeclaration", scope.imports) { if (it.sourceDeclaration != null) 1 else 0 }
        // Declarations are created before the scenario (previous scenarios may have created some of them already)
        val declarations = scope.declarations()
        scenario("assertTrue on declarations()", listOf(declarations)) { allDeclarations ->
            allDeclarations.assertTrue(testName = TEST_NAME) { true }
            allDeclarations.size
        }
        scenario("children()", childProviders) { it.children().size }
        scenario("parents(indirectParents = true)", parentProviders) { it.parents(indirectParents = true).size }
        scenario("children(indirectChildren = true)", childProviders) { it.children(indirectChildren = true).size }
    }

    private fun <T> scenario(
        name: String,
        items: List<T>,
        query: (T) -> Int,
    ) {
        if (queryStillRunning) {
            println("SCENARIO $name skipped: a query of the previous scenario is still running")
            return
        }

        val processed = AtomicInteger()
        val result = AtomicLong()
        val cpuNanos = AtomicLong(-1)
        val start = System.nanoTime()

        val worker =
            thread(isDaemon = true, name = "scenario") {
                val startCpuNanos = threads.currentThreadCpuTime

                for (item in items) {
                    if (elapsedSeconds(start) > budgetSeconds) {
                        break
                    }

                    result.addAndGet(query(item).toLong())
                    processed.incrementAndGet()
                }

                cpuNanos.set(threads.currentThreadCpuTime - startCpuNanos)
            }

        // The worker stops after the query running when the budget is exceeded, so it gets a moment to finish it
        worker.join(budgetSeconds * MILLIS_IN_SECOND + GRACE_PERIOD_MILLIS)
        queryStillRunning = worker.isAlive

        val budgetExceeded = if (processed.get() < items.size) " (budget exceeded)" else ""
        val cpu = cpuNanos.get().let { if (it < 0) "n/a" else "%.2fs".format(it / NANOS_IN_SECOND) }
        println(
            "SCENARIO $name items=${processed.get()}/${items.size} result=${result.get()} t=%.2fs cpu=$cpu$budgetExceeded"
                .format(elapsedSeconds(start)),
        )
    }

    private fun elapsedSeconds(start: Long): Double = (System.nanoTime() - start) / NANOS_IN_SECOND

    private companion object {
        val threads: ThreadMXBean = ManagementFactory.getThreadMXBean()

        const val TEST_NAME = "konsist-memory-benchmark"
        const val NANOS_IN_SECOND = 1e9
        const val MILLIS_IN_SECOND = 1000L
        const val GRACE_PERIOD_MILLIS = 5000L

        @Volatile
        var queryStillRunning = false
    }
}
