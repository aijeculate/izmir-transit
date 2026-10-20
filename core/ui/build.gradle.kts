plugins {
    alias(libs.plugins.izmir.android.library)
    alias(libs.plugins.izmir.android.compose)
}

android {
    namespace = "com.aijec.izmirtransit.core.ui"
}

dependencies {
    api(projects.core.model)
    implementation(libs.compose.material.icons.extended)
}
