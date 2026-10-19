plugins {
    alias(libs.plugins.izmir.android.library)
    alias(libs.plugins.izmir.android.hilt)
}

android {
    namespace = "com.aijec.izmirtransit.core.location"
}

dependencies {
    api(projects.core.model)
    implementation(libs.androidx.core.ktx)
    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.play.services)
}
