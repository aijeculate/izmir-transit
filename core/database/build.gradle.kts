plugins {
    alias(libs.plugins.izmir.kmp.library)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    android {
        namespace = "com.aijec.izmirtransit.core.database"
        compileSdk = 37
        minSdk = 26
    }
    sourceSets {
        commonMain.dependencies {
            api(libs.room.runtime)
            api(libs.sqlite.bundled)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

room {
    // Exported schemas are committed: they document migrations and the shipped DB.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspJvm", libs.room.compiler)
    add("kspAndroid", libs.room.compiler)
}
