package com.lemonappdev.konsistmemorybenchmark

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue

/**
 * Creates scope from the same directory repeatedly (e.g. in every test class) and prints the heap retained after
 * every iteration. Growing heap means that files parsed by previous scopes are not released.
 */
class DirectoryScopes(
    private val memory: Memory,
) {
    fun run(
        directory: String,
        iterations: Int,
    ) {
        repeat(iterations) { index ->
            val scope = Konsist.scopeFromDirectory(directory)
            scope.classes().assertTrue(testName = TEST_NAME) { c -> c.name.isNotEmpty() }
            scope.functions().assertTrue(testName = TEST_NAME) { f -> f.parameters.all { p -> p.type.name.isNotEmpty() } }
            memory.checkpoint("directoryScope#${index + 1}")
        }
    }

    private companion object {
        const val TEST_NAME = "konsist-memory-benchmark"
    }
}
