import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    application
}

// Konsist version (Maven Central or Maven Local) or path to Konsist jar, e.g. -Pkonsist=0.18.1
val konsist = providers.gradleProperty("konsist").getOrElse("0.18.1")

// Name of the installation directory (build/install/<variant>), so multiple Konsist versions can be installed side by side
val variant = providers.gradleProperty("variant").getOrElse(konsist.substringAfterLast('/').removeSuffix(".jar"))

// Dependencies of Konsist jar (jar file has no POM, so its dependencies are declared here).
// Konsist runs with the Kotlin compiler version of the project using it, so the version can be set for Konsist from
// Maven too (by default the version from Konsist POM is used).
val kotlinCompilerVersion = providers.gradleProperty("kotlinCompilerVersion").orNull
val defaultKotlinCompilerVersion = "2.4.21"
val coroutinesVersion = providers.gradleProperty("coroutinesVersion").getOrElse("1.9.0")

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    if (konsist.endsWith(".jar")) {
        implementation(files(konsist))
        implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:${kotlinCompilerVersion ?: defaultKotlinCompilerVersion}")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    } else {
        implementation("com.lemonappdev:konsist:$konsist")

        if (kotlinCompilerVersion != null) {
            implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable") {
                version { strictly(kotlinCompilerVersion) }
            }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.lemonappdev.konsistmemorybenchmark.MainKt")
}

tasks.installDist {
    destinationDir = layout.buildDirectory.dir("install/$variant").get().asFile
}
