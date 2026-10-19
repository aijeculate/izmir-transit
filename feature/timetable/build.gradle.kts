plugins {
    alias(libs.plugins.izmir.android.library)
    alias(libs.plugins.izmir.android.compose)
    alias(libs.plugins.izmir.android.hilt)
}

android {
    namespace = "com.aijec.izmirtransit.feature.timetable"
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.ui)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
}
