plugins {
    alias(libs.plugins.izmir.android.library)
    alias(libs.plugins.izmir.android.compose)
}

android {
    namespace = "com.aijec.izmirtransit.core.map"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.data)
    implementation(projects.core.ui)
    api(libs.maps.compose)
    api(libs.play.services.maps)
    implementation(libs.compose.material.icons.extended)
}
