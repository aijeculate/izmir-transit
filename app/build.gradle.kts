import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.izmir.android.application)
    alias(libs.plugins.izmir.android.compose)
    alias(libs.plugins.izmir.android.hilt)
}

android {
    namespace = "com.aijec.izmirtransit"
    defaultConfig {
        applicationId = "com.aijec.izmirtransit"
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        release {
            // R8: shrink, optimize and obfuscate; libraries ship their own keep rules.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    androidResources {
        // Turkish first, English second.
        localeFilters += listOf("tr", "en")
    }
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.ui)
    implementation(projects.feature.linemap)
    implementation(projects.feature.stop)
    implementation(projects.feature.home)
    implementation(projects.feature.timetable)
    implementation(libs.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.maplibre)
}

/** Copies the database built by :tools:importer into the app's generated assets. */
abstract class CopyTransitDatabase : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val database: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        database.get().asFile.copyTo(out.resolve("izmir-transit.db"), overwrite = true)
    }
}

val copyTransitDatabase = tasks.register<CopyTransitDatabase>("copyTransitDatabase") {
    // Rebuilds the DB only when research/raw or the importer code changed.
    dependsOn(":tools:importer:run")
    database.set(rootProject.layout.buildDirectory.file("izmir-transit.db"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyTransitDatabase, CopyTransitDatabase::outputDir)
    }
}
