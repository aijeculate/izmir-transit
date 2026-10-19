import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions

/** Bytecode level shared by every module; Android's D8 accepts 17. */
internal val JVM_TARGET = JvmTarget.JVM_17

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun KotlinJvmCompilerOptions.applyDefaults() {
    jvmTarget.set(JVM_TARGET)
    freeCompilerArgs.add("-Xjdk-release=17")
}

internal fun Project.configureTests() {
    tasks.withType<Test>().configureEach {
        useJUnit()
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
