plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.android.gradle.plugin)
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
        register("androidApplication") {
            id = "izmir.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "izmir.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "izmir.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "izmir.android.hilt"
            implementationClass = "AndroidHiltConventionPlugin"
        }
    }
}
