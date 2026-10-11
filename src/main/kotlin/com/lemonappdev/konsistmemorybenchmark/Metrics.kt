package com.lemonappdev.konsistmemorybenchmark

import com.lemonappdev.konsist.api.Konsist
import jdk.jfr.Recording
import java.io.File
import java.lang.management.ManagementFactory
import java.time.Duration

/**
 * Time and CPU usage of a single run. Short runs are dominated by JIT compilation, which can vary by seconds between
 * runs of the same version, so [queryCpuSeconds] and [queryAllocatedMb] are the most stable metrics.
 *
 * @property queryCpuSeconds CPU time of the thread running the queries (files parsed in parallel are not included)
 * @property cpuSeconds CPU time of the whole JVM (all threads, including JIT compiler and GC)
 * @property gcSeconds time spent in garbage collection
 * @property jitSeconds time spent by JIT compiler threads
 * @property queryAllocatedMb memory allocated by the thread running the queries
 */
data class RunMetrics(
    val wallSeconds: Double,
    val queryCpuSeconds: Double,
    val cpuSeconds: Double,
    val gcSeconds: Double,
    val jitSeconds: Double,
    val queryAllocatedMb: Double,
) {
    override fun toString(): String =
        "wall=%.2fs queryCpu=%.2fs cpu=%.2fs gc=%.2fs jit=%.2fs queryAllocMb=%.1f".format(
            wallSeconds,
            queryCpuSeconds,
            cpuSeconds,
            gcSeconds,
            jitSeconds,
            queryAllocatedMb,
        )

    companion object {
        fun median(runs: List<RunMetrics>): RunMetrics =
            RunMetrics(
                wallSeconds = runs.map { it.wallSeconds }.median(),
                queryCpuSeconds = runs.map { it.queryCpuSeconds }.median(),
                cpuSeconds = runs.map { it.cpuSeconds }.median(),
                gcSeconds = runs.map { it.gcSeconds }.median(),
                jitSeconds = runs.map { it.jitSeconds }.median(),
                queryAllocatedMb = runs.map { it.queryAllocatedMb }.median(),
            )

        private fun List<Double>.median(): Double = sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }
    }
}

/**
 * Runs the benchmark mode [warmupRuns] times without measuring (to warm up JIT and caches) and then [measuredRuns]
 * times, printing metrics of every measured run (`RUN <index> <metrics>`) and their median (`RUNS median <metrics>`).
 *
 * @param jfrFile when set, CPU samples of the measured runs are recorded to this JFR file
 */
class Runs(
    private val warmupRuns: Int,
    private val measuredRuns: Int,
    private val jfrFile: File?,
) {
    fun run(block: () -> Unit) {
        repeat(warmupRuns) { index ->
            block()
            println("WARMUP ${index + 1}")
        }

        val recording = jfrFile?.let { startRecording() }

        val metrics =
            (1..measuredRuns).map { index ->
                measure(block).also { println("RUN $index $it") }
            }

        recording?.let { stopRecording(it, requireNotNull(jfrFile)) }

        if (measuredRuns > 1) {
            println("RUNS median ${RunMetrics.median(metrics)}")
        }
    }

    private fun measure(block: () -> Unit): RunMetrics {
        val start = Snapshot.take()
        block()
        val end = Snapshot.take()

        return RunMetrics(
            wallSeconds = (end.wallNanos - start.wallNanos) / NANOS_IN_SECOND,
            queryCpuSeconds = (end.threadCpuNanos - start.threadCpuNanos) / NANOS_IN_SECOND,
            cpuSeconds = (end.processCpuNanos - start.processCpuNanos) / NANOS_IN_SECOND,
            gcSeconds = (end.gcMillis - start.gcMillis) / MILLIS_IN_SECOND,
            jitSeconds = (end.jitMillis - start.jitMillis) / MILLIS_IN_SECOND,
            queryAllocatedMb = (end.threadAllocatedBytes - start.threadAllocatedBytes) / BYTES_IN_MB,
        )
    }

    private fun startRecording(): Recording =
        Recording().apply {
            enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(SAMPLING_PERIOD_MILLIS))
            enable("jdk.ThreadCPULoad").withPeriod(Duration.ofMillis(THREAD_CPU_LOAD_PERIOD_MILLIS))
            enable("jdk.GarbageCollection")
            start()
        }

    private fun stopRecording(
        recording: Recording,
        file: File,
    ) {
        recording.stop()
        file.absoluteFile.parentFile.mkdirs()
        recording.dump(file.toPath())
        recording.close()
        println("JFR ${file.path}")
    }

    private class Snapshot(
        val wallNanos: Long,
        val threadCpuNanos: Long,
        val processCpuNanos: Long,
        val gcMillis: Long,
        val jitMillis: Long,
        val threadAllocatedBytes: Long,
    ) {
        companion object {
            private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            private val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean

            fun take(): Snapshot =
                Snapshot(
                    wallNanos = System.nanoTime(),
                    threadCpuNanos = threads.currentThreadCpuTime,
                    processCpuNanos = os.processCpuTime,
                    gcMillis = ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime.coerceAtLeast(0) },
                    jitMillis = ManagementFactory.getCompilationMXBean()?.totalCompilationTime ?: 0,
                    threadAllocatedBytes = threads.currentThreadAllocatedBytes,
                )
        }
    }

    private companion object {
        const val SAMPLING_PERIOD_MILLIS = 1L
        const val THREAD_CPU_LOAD_PERIOD_MILLIS = 100L
        const val NANOS_IN_SECOND = 1e9
        const val MILLIS_IN_SECOND = 1e3
        const val BYTES_IN_MB = 1024.0 * 1024.0
    }
}

/**
 * Prints the environment of the benchmark, so results can be compared only with results from the same environment.
 * Timing is not reliable when other processes load the machine (load average higher than the number of CPUs).
 */
object Environment {
    private val os = ManagementFactory.getOperatingSystemMXBean()

    fun printStart() {
        val runtime = Runtime.getRuntime()

        println(
            "ENV variant=${System.getenv("BENCHMARK_VARIANT") ?: "unknown"} konsist=${konsistVersion()} " +
                "kotlinCompiler=${kotlinCompilerVersion()} java=${System.getProperty("java.version")} " +
                "os=${System.getProperty("os.name").replace(' ', '_')}-${System.getProperty("os.arch")} " +
                "cpus=${runtime.availableProcessors()} maxHeapMb=${runtime.maxMemory() / BYTES_IN_MB} " +
                "load=${loadAverage()}",
        )
    }

    fun printEnd() {
        val load = os.systemLoadAverage
        val cpus = Runtime.getRuntime().availableProcessors()
        println("LOAD end=${loadAverage()}")

        if (load > cpus) {
            println("WARNING load average ${loadAverage()} is higher than the number of CPUs ($cpus), timing is not reliable")
        }
    }

    private fun loadAverage(): String = os.systemLoadAverage.let { if (it < 0) "unknown" else "%.1f".format(it) }

    private fun konsistVersion(): String = Konsist::class.java.`package`?.implementationVersion ?: "unknown"

    // Konsist runs with the Kotlin compiler version of the project using it (kotlin-compiler-embeddable)
    private fun kotlinCompilerVersion(): String =
        runCatching {
            Class
                .forName("org.jetbrains.kotlin.config.KotlinCompilerVersion")
                .getField("VERSION")
                .get(null) as String
        }.getOrDefault("unknown")

    private const val BYTES_IN_MB = 1024 * 1024
}
