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
private const val DEFAULT_MODULE_COUNT = 10
private const val DEFAULT_SCENARIO_BUDGET_SECONDS = 60
private const val DEFAULT_CHILDREN_SAMPLE_SIZE = 10

private val USAGE =
    """
    Usage: konsist-memory-benchmark <mode> [mode arguments] [options]

    Konsist creates the project scope from the current working directory, so run the benchmark in the root
    directory of the benchmarked project.

    Modes:
      suite                      Run typical Konsist test suite
      steps                      Print heap retained after each query kind
      directory <path> [count]   Create scope from the same directory repeatedly (default count: $DEFAULT_DIRECTORY_SCOPE_ITERATIONS)
      modules [count | path...]  Create scopes of the biggest modules (or given directories) one after another and resolve
                                 types; no project scope is created (default count: $DEFAULT_MODULE_COUNT)
      scenarios [budget-sec]     Run queries that were slow in some Konsist versions and print time of each; every
                                 scenario stops after the time budget (default: $DEFAULT_SCENARIO_BUDGET_SECONDS s)
      dump <file>                Write query results to the file (compare two Konsist versions with diff)
      resolve <file> [children]  Write type resolution results to the file: fully qualified names, parents, source
                                 declarations of types, imports and annotations, and children of the given number
                                 of the most common parents (default: $DEFAULT_CHILDREN_SAMPLE_SIZE)

    Options:
      --histogram <file>         Write class histogram of the heap at the end
      --no-gc                    Do not run GC at checkpoints (use when measuring time or searching for the smallest heap)
      --warmup <count>           Run the mode the given number of times before the measured runs (suite, modules, scenarios)
      --runs <count>             Number of measured runs (suite, modules, scenarios); metrics of every run are printed:
                                 wall time, CPU time of the query thread and of the JVM, GC and JIT time, allocations
      --jfr <file>               Record CPU samples of the measured runs to the file (see scripts/jfr-summary.py)
    """.trimIndent()

private val MODES = listOf("suite", "steps", "directory", "modules", "scenarios", "dump", "resolve")

// Modes measuring scopes created from parts of the project don't create the project scope first
private val MODES_WITHOUT_PROJECT_SCOPE = listOf("modules", "scenarios")

fun main(args: Array<String>) {
    disableLogging()

    val arguments = args.toMutableList()
    val forceGc = !arguments.remove("--no-gc")
    val histogramFile = takeOption(arguments, "--histogram")?.let { resolveFile(it) }
    val warmupRuns = takeOption(arguments, "--warmup")?.toInt() ?: 0
    val measuredRuns = takeOption(arguments, "--runs")?.toInt() ?: 1
    val jfrFile = takeOption(arguments, "--jfr")?.let { resolveFile(it) }
    val mode = arguments.removeFirstOrNull()

    if (mode == null || mode !in MODES) {
        System.err.println(USAGE)
        exitProcess(EXIT_ERROR)
    }

    val memory = Memory(forceGc)
    val runs = Runs(warmupRuns, measuredRuns, jfrFile)

    try {
        Environment.printStart()
        memory.checkpoint("start")

        val scope =
            if (mode in MODES_WITHOUT_PROJECT_SCOPE) {
                null
            } else {
                Konsist.scopeFromProject().also {
                    println("FILES ${it.files.size}")
                    memory.checkpoint("scopeFromProject")
                }
            }

        when (mode) {
            "suite" -> {
                runs.run { Suite(memory).run(requireNotNull(scope)) }
            }

            "steps" -> {
                Steps(memory).run(requireNotNull(scope))
            }

            "directory" -> {
                val directory = requireNotNull(arguments.getOrNull(0)) { "Missing directory path" }
                val iterations = arguments.getOrNull(1)?.toInt() ?: DEFAULT_DIRECTORY_SCOPE_ITERATIONS
                DirectoryScopes(memory).run(directory, iterations)
            }

            "modules" -> {
                val modules = getModules(arguments)
                println("MODULES ${modules.joinToString(" ")}")
                runs.run { ModuleScopes(memory).run(modules) }
            }

            "scenarios" -> {
                val budgetSeconds = arguments.getOrNull(0)?.toInt() ?: DEFAULT_SCENARIO_BUDGET_SECONDS
                runs.run { Scenarios(budgetSeconds).run() }
            }

            "dump" -> {
                val output = resolveFile(requireNotNull(arguments.getOrNull(0)) { "Missing output file" })
                ResultDump().write(requireNotNull(scope), output)
            }

            "resolve" -> {
                val output = resolveFile(requireNotNull(arguments.getOrNull(0)) { "Missing output file" })
                val childrenSampleSize = arguments.getOrNull(1)?.toInt() ?: DEFAULT_CHILDREN_SAMPLE_SIZE
                ResolutionDump(childrenSampleSize).write(requireNotNull(scope), output)
            }
        }

        histogramFile?.let { memory.writeHistogram(it) }

        Environment.printEnd()
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

/**
 * Module directories passed as arguments or the biggest modules of the project (the working directory).
 */
private fun getModules(arguments: List<String>): List<String> {
    val count = arguments.singleOrNull()?.toIntOrNull()

    return if (arguments.isEmpty() || count != null) {
        ModuleScopes.findBiggestModules(File("").absoluteFile, count ?: DEFAULT_MODULE_COUNT)
    } else {
        arguments
    }
}
