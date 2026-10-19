plugins {
    alias(libs.plugins.izmir.android.library)
    alias(libs.plugins.izmir.android.compose)
    alias(libs.plugins.izmir.android.hilt)
}

android {
    namespace = "com.aijec.izmirtransit.feature.home"
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.ui)
    implementation(projects.core.location)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
}
