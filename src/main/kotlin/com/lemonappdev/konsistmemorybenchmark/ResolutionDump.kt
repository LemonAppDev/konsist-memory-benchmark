package com.lemonappdev.konsistmemorybenchmark

import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.declaration.KoClassDeclaration
import com.lemonappdev.konsist.api.declaration.KoEnumConstantDeclaration
import com.lemonappdev.konsist.api.declaration.KoExternalDeclaration
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.declaration.KoFunctionDeclaration
import com.lemonappdev.konsist.api.declaration.KoImportAliasDeclaration
import com.lemonappdev.konsist.api.declaration.KoInterfaceDeclaration
import com.lemonappdev.konsist.api.declaration.KoObjectDeclaration
import com.lemonappdev.konsist.api.declaration.KoPropertyDeclaration
import com.lemonappdev.konsist.api.declaration.KoTypeAliasDeclaration
import com.lemonappdev.konsist.api.declaration.KoTypeParameterDeclaration
import com.lemonappdev.konsist.api.declaration.combined.KoClassAndInterfaceAndObjectDeclaration
import com.lemonappdev.konsist.api.declaration.type.KoKotlinTypeDeclaration
import com.lemonappdev.konsist.api.provider.KoAnnotationProvider
import com.lemonappdev.konsist.api.provider.KoChildProvider
import com.lemonappdev.konsist.api.provider.KoFullyQualifiedNameProvider
import com.lemonappdev.konsist.api.provider.KoNameProvider
import com.lemonappdev.konsist.api.provider.KoParentProvider
import java.io.File

/**
 * Writes type resolution results for every file of the scope, so results of two Konsist versions can be compared
 * with `diff`. Covers queries that [ResultDump] does not: fully qualified names, direct and indirect parents, source
 * declarations of types, imports and annotations, and children of the most common parents (children are searched in
 * the whole project, so they are written only for [childrenSampleSize] declarations).
 *
 * Line format: `<query> <file path> <index> <name> <result>` (tab separated). Source declaration is written as
 * `<kind>:<fully qualified name>`, e.g. `class:com.app.Foo` or `external:org.junit.Test`.
 */
class ResolutionDump(
    private val childrenSampleSize: Int,
) {
    private val whitespace = Regex("\\s+")

    fun write(
        scope: KoScope,
        output: File,
    ) {
        output.absoluteFile.parentFile.mkdirs()

        output.bufferedWriter().use { writer ->
            scope.files.forEach { file ->
                catchErrors(file.path) { getLines(file) }.forEach { writer.appendLine(it) }
            }

            catchErrors("children") { getChildrenLines(scope) }.forEach { writer.appendLine(it) }
        }
    }

    private fun getLines(file: KoFileDeclaration): List<String> =
        buildList {
            val types = file.classesAndInterfacesAndObjects(includeNested = true, includeLocal = false)

            types.forEachIndexed { index, type ->
                add(line("fullyQualifiedName", file.path, index, type.name, type.fullyQualifiedName))

                (type as? KoParentProvider)?.let { parentProvider ->
                    parentProvider.parents().forEach { add(line("parent", file.path, index, it.name, describe(it.sourceDeclaration))) }

                    val indirectParents = parentProvider.parents(indirectParents = true).joinToString(", ") { describe(it.sourceDeclaration) }
                    add(line("indirectParents", file.path, index, type.name, indirectParents))
                }
            }

            file.functions(includeNested = true, includeLocal = false).forEachIndexed { index, function ->
                add(line("returnType", file.path, index, function.name, describe(function.returnType?.sourceDeclaration)))

                function.parameters.forEach {
                    add(line("parameterType", file.path, index, "${function.name}.${it.name}", describe(it.type.sourceDeclaration)))
                }
            }

            file.properties(includeNested = true).forEachIndexed { index, property ->
                add(line("propertyType", file.path, index, property.name, describe(property.type?.sourceDeclaration)))
            }

            file.imports.forEachIndexed { index, import ->
                add(line("import", file.path, index, import.name, describe(import.sourceDeclaration)))
            }

            (listOf(file) + types).flatMap { (it as? KoAnnotationProvider)?.annotations.orEmpty() }.forEachIndexed { index, annotation ->
                add(line("annotation", file.path, index, annotation.name, annotation.fullyQualifiedName))
            }
        }

    /*
     * Children of the most common parents (declarations with most direct children), so the lists are not empty.
     */
    private fun getChildrenLines(scope: KoScope): List<String> {
        if (childrenSampleSize <= 0) {
            return emptyList()
        }

        val types = scope.classesAndInterfacesAndObjects(includeNested = true, includeLocal = false)

        val childrenCounts =
            types
                .flatMap { (it as? KoParentProvider)?.parents().orEmpty() }
                .mapNotNull { (it.sourceDeclaration as? KoFullyQualifiedNameProvider)?.fullyQualifiedName }
                .groupingBy { it }
                .eachCount()

        val sample =
            types
                .filter { it is KoChildProvider && it.fullyQualifiedName in childrenCounts }
                .sortedWith(
                    compareByDescending<KoClassAndInterfaceAndObjectDeclaration> { childrenCounts[it.fullyQualifiedName] }
                        .thenBy { it.fullyQualifiedName },
                ).take(childrenSampleSize)

        return sample.flatMapIndexed { index, type ->
            val childProvider = type as KoChildProvider

            listOf(
                line("children", type.path, index, type.name, childProvider.children().joinToString(", ") { describe(it) }),
                line(
                    "indirectChildren",
                    type.path,
                    index,
                    type.name,
                    childProvider.children(indirectChildren = true).joinToString(", ") { describe(it) },
                ),
            )
        }
    }

    private fun line(
        query: String,
        path: String,
        index: Int,
        name: String?,
        result: String?,
    ): String = "$query\t$path\t$index\t${name.toSingleLine()}\t${result.toSingleLine()}"

    // Names of some types span multiple lines, e.g. function type with parameters on separate lines
    private fun String?.toSingleLine(): String = toString().replace(whitespace, " ")

    private fun catchErrors(
        context: String,
        lines: () -> List<String>,
    ): List<String> =
        try {
            lines()
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            listOf("ERROR\t$context\t${e.javaClass.name}")
        }

    /*
     * Kind is taken from the API interface (not the implementation class name), so it doesn't change when Konsist
     * implementation classes are renamed.
     */
    private fun describe(declaration: Any?): String {
        if (declaration == null) {
            return "null"
        }

        val kind =
            when (declaration) {
                is KoClassDeclaration -> "class"
                is KoInterfaceDeclaration -> "interface"
                is KoObjectDeclaration -> "object"
                is KoTypeAliasDeclaration -> "typealias"
                is KoImportAliasDeclaration -> "importAlias"
                is KoExternalDeclaration -> "external"
                is KoKotlinTypeDeclaration -> "kotlin"
                is KoTypeParameterDeclaration -> "typeParameter"
                is KoFunctionDeclaration -> "function"
                is KoPropertyDeclaration -> "property"
                is KoEnumConstantDeclaration -> "enumConstant"
                else -> declaration::class.simpleName.orEmpty().removeSuffix("Core")
            }

        val name = (declaration as? KoFullyQualifiedNameProvider)?.fullyQualifiedName ?: (declaration as? KoNameProvider)?.name

        return "$kind:$name"
    }
}
