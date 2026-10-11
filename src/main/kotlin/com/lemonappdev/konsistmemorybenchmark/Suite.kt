package com.lemonappdev.konsistmemorybenchmark

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.architecture.KoArchitectureCreator.assertArchitecture
import com.lemonappdev.konsist.api.architecture.Layer
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.ext.list.modifierprovider.withPublicOrDefaultModifier
import com.lemonappdev.konsist.api.ext.list.withAnnotationNamed
import com.lemonappdev.konsist.api.ext.list.withNameEndingWith
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue

/**
 * Typical Konsist test suite: every rule calls `Konsist.scopeFromProject()` (like separate tests) and checks a single
 * rule. Failing rules are expected (e.g. not every function has KDoc) and exercise failure messages.
 */
class Suite(
    private val memory: Memory,
) {
    private var failedRules = 0

    fun run(scope: KoScope) {
        val layers = getTopPackages(scope)
        println("LAYERS $layers")

        rule("classes have names") { it.classes().assertTrue(testName = TEST_NAME) { c -> c.name.isNotEmpty() } }
        rule("interface names start with uppercase") {
            it.interfaces().assertTrue(testName = TEST_NAME) { c -> c.name.first().isUpperCase() }
        }
        rule("objects have names") { it.objects().assertTrue(testName = TEST_NAME) { c -> c.name.isNotEmpty() } }
        rule("functions have names") { it.functions().assertFalse(testName = TEST_NAME) { f -> f.name.isEmpty() } }
        rule("no public var properties") {
            it.properties().assertFalse(testName = TEST_NAME) { p -> p.isVar && p.hasPublicModifier }
        }
        rule("no wildcard imports") { it.imports.assertFalse(testName = TEST_NAME) { i -> i.isWildcard } }
        rule("package matches path") {
            it.files.assertTrue(testName = TEST_NAME) { f -> f.packagee?.hasMatchingPath ?: true }
        }
        rule("classes reside in package") { it.classes().assertTrue(testName = TEST_NAME) { c -> c.resideInPackage("..") } }
        rule("Impl classes have parents") {
            it.classes().withNameEndingWith("Impl").assertTrue(testName = TEST_NAME) { c -> c.parents().isNotEmpty() }
        }
        rule("class parents are resolved") {
            it.classes().assertTrue(testName = TEST_NAME) { c -> c.parents().all { p -> p.name.isNotEmpty() } }
        }
        rule("function parameters have types") {
            it.functions().assertTrue(testName = TEST_NAME) { f -> f.parameters.all { p -> p.type.name.isNotEmpty() } }
        }
        rule("property types are not Nothing") {
            it.properties().assertTrue(testName = TEST_NAME) { p -> p.type?.name != "Nothing" }
        }
        rule("public functions have KDoc") {
            it
                .functions(includeNested = false, includeLocal = false)
                .withPublicOrDefaultModifier()
                .assertTrue(testName = TEST_NAME) { f -> f.hasKDoc }
        }
        rule("test functions have names") {
            it.functions().withAnnotationNamed("Test").assertTrue(testName = TEST_NAME) { f -> f.name.isNotEmpty() }
        }
        rule("annotations have names") { it.annotations.assertTrue(testName = TEST_NAME) { a -> a.name.isNotEmpty() } }
        rule("classes have text") { it.classes().assertTrue(testName = TEST_NAME) { c -> c.text.isNotEmpty() } }
        rule("declarations have string representation") {
            it.declarations().assertTrue(testName = TEST_NAME) { d -> d.toString().isNotEmpty() }
        }
        rule("production classes have names") {
            Konsist.scopeFromProduction().classes().assertTrue(testName = TEST_NAME) { c -> c.name.isNotEmpty() }
        }
        rule("test classes have names") {
            Konsist.scopeFromTest().classes().assertTrue(testName = TEST_NAME) { c -> c.name.isNotEmpty() }
        }

        if (layers.size >= 2) {
            rule("architecture") { scope ->
                scope.assertArchitecture {
                    val definedLayers = layers.mapIndexed { index, packageName -> Layer("Layer$index", "$packageName..") }
                    definedLayers.first().dependsOnNothing()
                    definedLayers.drop(1).forEach { it.dependsOn(definedLayers.first()) }
                }
            }
        }

        memory.checkpoint("suite")
        println("FAILED_RULES $failedRules")
    }

    private fun rule(
        name: String,
        check: (KoScope) -> Unit,
    ) {
        val scope = Konsist.scopeFromProject()

        try {
            check(scope)
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            if (e.javaClass.simpleName != "KoAssertionFailedException") {
                println("RULE '$name' threw ${e.javaClass.name}: ${e.message?.take(MAX_MESSAGE_LENGTH)}")
            }

            failedRules++
        }
    }

    /**
     * Three most common package prefixes (first four segments) used as architecture layers.
     */
    private fun getTopPackages(scope: KoScope): List<String> =
        scope
            .files
            .mapNotNull { it.packagee?.name }
            .map { it.split('.').take(LAYER_PACKAGE_SEGMENTS).joinToString(".") }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(LAYERS)
            .map { it.key }

    private companion object {
        // Test name is passed explicitly, because Konsist can't take it from the JUnit test method here
        const val TEST_NAME = "konsist-memory-benchmark"
        const val MAX_MESSAGE_LENGTH = 300
        const val LAYER_PACKAGE_SEGMENTS = 4
        const val LAYERS = 3
    }
}
