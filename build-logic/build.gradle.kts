plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = "izmir.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("kmpLibrary") {
            id = "izmir.kmp.library"
            implementationClass = "KmpLibraryConventionPlugin"
        }
    }
}
