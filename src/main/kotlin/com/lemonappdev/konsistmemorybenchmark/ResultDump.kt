package com.lemonappdev.konsistmemorybenchmark

import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.provider.KoNameProvider
import com.lemonappdev.konsist.api.provider.KoTextProvider
import java.io.File

/**
 * Writes results of common queries for every file of the scope (one line per declaration), so results of two Konsist
 * versions can be compared with `diff`.
 *
 * Line format: `<query> <file path> <index> <declaration class> <name> <text hash>` (tab separated).
 */
class ResultDump {
    fun write(
        scope: KoScope,
        output: File,
    ) {
        output.absoluteFile.parentFile.mkdirs()

        output.bufferedWriter().use { writer ->
            scope.files.forEach { file ->
                try {
                    getLines(file).forEach { writer.appendLine(it) }
                } catch (e: OutOfMemoryError) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Throwable,
                ) {
                    writer.appendLine("ERROR\t${file.path}\t${e.javaClass.name}")
                }
            }
        }
    }

    private fun getLines(file: KoFileDeclaration): List<String> {
        val queries =
            listOf(
                "classes" to file.classes(),
                "classesNoLocal" to file.classes(includeLocal = false),
                "interfaces" to file.interfaces(),
                "objects" to file.objects(),
                "companionObjects" to file.companionObjects(),
                "functions" to file.functions(),
                "functionsNoNested" to file.functions(includeNested = false),
                "properties" to file.properties(),
                "declarations" to file.declarations(),
                "declarationsNoNested" to file.declarations(includeNested = false),
                "classesAndInterfacesAndObjects" to file.classesAndInterfacesAndObjects(),
                "imports" to file.imports,
            )

        return queries.flatMap { (query, items) ->
            items.mapIndexed { index, item ->
                val name = (item as? KoNameProvider)?.name
                val textHash = (item as? KoTextProvider)?.text?.hashCode()
                "$query\t${file.path}\t$index\t${item::class.simpleName}\t$name\t$textHash"
            }
        }
    }
}
