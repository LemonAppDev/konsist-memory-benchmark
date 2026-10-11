package com.lemonappdev.konsistmemorybenchmark

import com.lemonappdev.konsist.api.container.KoScope

/**
 * Runs queries one by one and prints the heap retained after each of them, so memory can be attributed to
 * query kinds (values are cumulative, because declarations stay cached).
 */
class Steps(
    private val memory: Memory,
) {
    fun run(scope: KoScope) {
        step("classes(includeLocal=false)") { scope.classes(includeNested = true, includeLocal = false).size }
        step("functions(includeLocal=false)") { scope.functions(includeNested = true, includeLocal = false).size }
        step("properties()") { scope.properties().size }
        step("classes()") { scope.classes().size }
        step("functions()") { scope.functions().size }
        step("declarations()") { scope.declarations().size }
        step("functions().parameters.type") {
            scope.functions().sumOf { f -> f.parameters.count { p -> p.type.name.isNotEmpty() } }
        }
        step("classes().parents()") { scope.classes().sumOf { c -> c.parents().size } }
        step("declarations().toString()") { scope.declarations().count { d -> d.toString().isNotEmpty() } }
    }

    private fun step(
        label: String,
        query: () -> Int,
    ) {
        println("COUNT $label ${query()}")
        memory.checkpoint(label)
    }
}
