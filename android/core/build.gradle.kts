// Pure Kotlin: scoring, domain lists and DNS packet handling. No Android
// dependencies, so it is unit-tested on the JVM.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
dependencies {
    testImplementation(kotlin("test"))
}
tasks.test { useJUnitPlatform() }
