package com.lemonappdev.konsistmemorybenchmark

import com.lemonappdev.konsist.api.Konsist
import java.io.File
import java.util.logging.Level
import java.util.logging.LogManager
import java.util.logging.Logger
import kotlin.system.exitProcess

private const val EXIT_ERROR = 1
private const val EXIT_OUT_OF_MEMORY = 3
private const val DEFAULT_DIRECTORY_SCOPE_ITERATIONS = 5

private val USAGE =
    """
    Usage: konsist-memory-benchmark <mode> [options]

    Konsist creates the project scope from the current working directory, so run the benchmark in the root
    directory of the benchmarked project.

    Modes:
      suite                      Run typical Konsist test suite
      steps                      Print heap retained after each query kind
      directory <path> [count]   Create scope from the same directory repeatedly (default count: $DEFAULT_DIRECTORY_SCOPE_ITERATIONS)
      dump <file>                Write query results to the file (compare two Konsist versions with diff)

    Options:
      --histogram <file>         Write class histogram of the heap at the end
      --no-gc                    Do not run GC at checkpoints (use when measuring time or searching for the smallest heap)
    """.trimIndent()

fun main(args: Array<String>) {
    disableLogging()

    val arguments = args.toMutableList()
    val forceGc = !arguments.remove("--no-gc")
    val histogramFile = takeOption(arguments, "--histogram")?.let { resolveFile(it) }
    val mode = arguments.removeFirstOrNull()

    if (mode == null || mode !in listOf("suite", "steps", "directory", "dump")) {
        System.err.println(USAGE)
        exitProcess(EXIT_ERROR)
    }

    val memory = Memory(forceGc)

    try {
        memory.checkpoint("start")

        val scope = Konsist.scopeFromProject()
        println("FILES ${scope.files.size}")
        memory.checkpoint("scopeFromProject")

        when (mode) {
            "suite" -> {
                Suite(memory).run(scope)
            }

            "steps" -> {
                Steps(memory).run(scope)
            }

            "directory" -> {
                val directory = requireNotNull(arguments.getOrNull(0)) { "Missing directory path" }
                val iterations = arguments.getOrNull(1)?.toInt() ?: DEFAULT_DIRECTORY_SCOPE_ITERATIONS
                DirectoryScopes(memory).run(directory, iterations)
            }

            "dump" -> {
                val output = resolveFile(requireNotNull(arguments.getOrNull(0)) { "Missing output file" })
                ResultDump().write(scope, output)
            }
        }

        histogramFile?.let { memory.writeHistogram(it) }

        println("RESULT OK t=${memory.elapsedSeconds()}s")
        exitProcess(0)
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Throwable,
    ) {
        // Out of memory error can be wrapped (e.g. when it is thrown while files are parsed in parallel)
        if (e.causes().any { it is OutOfMemoryError }) {
            println("RESULT OOM t=${memory.elapsedSeconds()}s")
            exitProcess(EXIT_OUT_OF_MEMORY)
        }

        e.printStackTrace()
        println("RESULT ERROR ${e.javaClass.name}: ${e.message}")
        exitProcess(EXIT_ERROR)
    }
}

/**
 * Kotlin compiler logs (via java.util.logging) every class that is not available on the classpath, which floods
 * the output.
 */
private fun disableLogging() {
    LogManager.getLogManager().reset()
    Logger.getLogger("").level = Level.OFF
}

private fun takeOption(
    arguments: MutableList<String>,
    name: String,
): String? {
    val index = arguments.indexOf(name)

    if (index == -1) {
        return null
    }

    val value = requireNotNull(arguments.getOrNull(index + 1)) { "Missing value of $name option" }
    arguments.removeAt(index + 1)
    arguments.removeAt(index)
    return value
}

/**
 * Relative paths are resolved against the directory the benchmark was started from (`scripts/run.sh` changes the
 * working directory to the benchmarked project).
 */
private fun resolveFile(path: String): File {
    val file = File(path)

    if (file.isAbsolute) {
        return file
    }

    val baseDirectory = System.getenv("BENCHMARK_CALLER_DIR")?.let { File(it) } ?: File("").absoluteFile
    return File(baseDirectory, path)
}

private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }
