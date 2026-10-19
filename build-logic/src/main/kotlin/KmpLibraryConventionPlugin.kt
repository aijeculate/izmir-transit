import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Kotlin Multiplatform module. Only the JVM target exists for now, which lets the
 * desktop importer tool share the Room schema with the app. Add the Android target
 * here (com.android.kotlin.multiplatform.library) once the SDK is installed.
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")

        extensions.configure<KotlinMultiplatformExtension> {
            jvm {
                compilerOptions.applyDefaults()
            }
            sourceSets.getByName("jvmTest").dependencies {
                implementation(libs.findLibrary("kotlin-test").get())
                implementation(libs.findLibrary("junit").get())
            }
        }
        configureTests()
    }
}
