package com.lemonappdev.konsistmemorybenchmark

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.provider.KoParentProvider
import java.io.File

/**
 * Creates scopes of modules one after another (like test classes that check one module each) and resolves source
 * declarations of parents and of parameter, return and property types. No project scope is created, so files of
 * other modules are parsed only when Konsist needs them to resolve types.
 *
 * Unlike the suite (where every rule uses the whole project scope), this mode shows whether Konsist releases parsed
 * files that are no longer used and whether type resolution parses only the files it needs.
 */
class ModuleScopes(
    private val memory: Memory,
) {
    fun run(modules: List<String>) {
        var resolvedTypes = 0

        modules.forEach { module ->
            val scope = Konsist.scopeFromDirectory(module)
            resolvedTypes += resolveTypes(scope)
            memory.checkpoint("module $module")
        }

        println("COUNT resolved types $resolvedTypes")
    }

    private fun resolveTypes(scope: KoScope): Int {
        val parents =
            scope
                .classesAndInterfacesAndObjects(includeNested = true, includeLocal = false)
                .flatMap { (it as? KoParentProvider)?.parents().orEmpty() }
                .count { it.sourceDeclaration != null }

        val functionTypes =
            scope.functions(includeNested = true, includeLocal = false).sumOf { function ->
                function.parameters.count { it.type.sourceDeclaration != null } + if (function.returnType?.sourceDeclaration != null) 1 else 0
            }

        val propertyTypes = scope.properties(includeNested = true).count { it.type?.sourceDeclaration != null }

        return parents + functionTypes + propertyTypes
    }

    companion object {
        private val BUILD_FILES = setOf("build.gradle.kts", "build.gradle", "pom.xml")
        private val IGNORED_DIRECTORIES = setOf("build", "target", "node_modules")

        /**
         * Returns paths (relative to [projectDir]) of the [count] modules with the most Kotlin files.
         *
         * Module is a directory with a Gradle or Maven build file. Projects without build files (e.g. Kotlin compiler
         * sources copied without them) use directories containing the `src` directory. Only leaf modules (without
         * nested modules) are used, so module scopes do not overlap.
         */
        fun findBiggestModules(
            projectDir: File,
            count: Int,
        ): List<String> {
            val directories = projectDir.walkTopDown().onEnter { it == projectDir || !isIgnored(it) }.filter { it.isDirectory }.toList()

            val moduleDirectories =
                directories
                    .filter { it != projectDir && it.listFiles().orEmpty().any { file -> file.name in BUILD_FILES } }
                    .ifEmpty { directories.filter { it != projectDir && File(it, "src").isDirectory } }

            val leafModules =
                moduleDirectories.filter { module ->
                    moduleDirectories.none { other -> other != module && other.startsWith(module) }
                }

            return leafModules
                .map { it to countKotlinFiles(it) }
                .filter { (_, kotlinFiles) -> kotlinFiles > 0 }
                .sortedWith(compareByDescending<Pair<File, Int>> { it.second }.thenBy { it.first.path })
                .take(count)
                .map { (module, _) -> module.relativeTo(projectDir).invariantSeparatorsPath }
        }

        private fun isIgnored(directory: File): Boolean = directory.name.startsWith(".") || directory.name in IGNORED_DIRECTORIES

        private fun countKotlinFiles(directory: File): Int =
            directory
                .walkTopDown()
                .onEnter { it == directory || !isIgnored(it) }
                .count { it.isFile && it.extension == "kt" }
    }
}
