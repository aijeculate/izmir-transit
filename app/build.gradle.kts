import java.util.Properties
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
        versionCode = 2
        versionName = "1.0.1"
        // Google keys from local.properties (MAPS_API_KEY=…, PLACES_API_KEY=…), never committed.
        // Places falls back to the Maps key when one key has both APIs.
        val localProperties = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        val mapsKey = localProperties.getProperty("MAPS_API_KEY").orEmpty()
        manifestPlaceholders["mapsApiKey"] = mapsKey
        val placesKey = localProperties.getProperty("PLACES_API_KEY")?.takeIf { it.isNotBlank() } ?: mapsKey
        buildConfigField("String", "PLACES_API_KEY", "\"$placesKey\"")
    }
    buildFeatures {
        buildConfig = true
    }
    // Release signing from keystore.properties (storeFile, storePassword, keyAlias,
    // keyPassword), which is git-ignored. Without it the release APK is left unsigned.
    val keystoreProperties = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    val releaseSigning = keystoreProperties.getProperty("storeFile")?.let { store ->
        signingConfigs.create("release") {
            storeFile = rootProject.file(store)
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            signingConfig = releaseSigning
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
    implementation(libs.androidx.appcompat)
    implementation(projects.core.data)
    implementation(projects.core.ui)
    implementation(projects.feature.linemap)
    implementation(projects.feature.stop)
    implementation(projects.feature.home)
    implementation(projects.feature.timetable)
    implementation(projects.feature.settings)
    implementation(projects.feature.planner)
    implementation(libs.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(projects.core.map)
    implementation(libs.compose.material.icons.extended)
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
