package com.lemonappdev.konsistmemorybenchmark

import java.io.File
import java.lang.management.ManagementFactory
import javax.management.ObjectName

/**
 * Prints checkpoints with the heap retained at that point and time elapsed since the start.
 *
 * @param forceGc Whether to run full GC before reading used heap. GC is skipped when measuring time or searching
 * for the smallest heap, because forced GC changes both.
 */
class Memory(
    private val forceGc: Boolean,
) {
    private val startNanos = System.nanoTime()

    private val memoryBean = ManagementFactory.getMemoryMXBean()

    fun checkpoint(label: String) {
        if (forceGc) {
            println("CHECKPOINT $label usedAfterGcMb=${usedHeapAfterGc().toMb()} t=${elapsedSeconds()}s")
        } else {
            println("CHECKPOINT $label t=${elapsedSeconds()}s")
        }
    }

    fun elapsedSeconds(): String = "%.1f".format((System.nanoTime() - startNanos) / NANOS_IN_SECOND)

    /**
     * Writes class histogram of live objects (`jcmd <pid> GC.class_histogram`) to the [file].
     */
    fun writeHistogram(file: File) {
        val histogram =
            ManagementFactory.getPlatformMBeanServer().invoke(
                ObjectName("com.sun.management:type=DiagnosticCommand"),
                "gcClassHistogram",
                arrayOf<Any>(arrayOf<String>()),
                arrayOf(Array<String>::class.java.name),
            ) as String

        file.absoluteFile.parentFile.mkdirs()
        file.writeText(histogram)
    }

    private fun usedHeapAfterGc(): Long {
        // Multiple GC runs release objects that are reachable only from finalizers and cleaners
        repeat(GC_RUNS) {
            System.gc()
            Thread.sleep(GC_PAUSE_MILLIS)
        }

        return memoryBean.heapMemoryUsage.used
    }

    private fun Long.toMb() = "%.1f".format(this / BYTES_IN_MB)

    private companion object {
        const val GC_RUNS = 3
        const val GC_PAUSE_MILLIS = 30L
        const val NANOS_IN_SECOND = 1e9
        const val BYTES_IN_MB = 1024.0 * 1024.0
    }
}
