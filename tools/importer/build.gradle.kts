plugins {
    alias(libs.plugins.izmir.jvm.library)
    application
}

application {
    mainClass.set("com.aijec.izmirtransit.tools.importer.MainKt")
}

dependencies {
    implementation(projects.core.data)
}

tasks.named<JavaExec>("run") {
    // Relative --raw/--out paths resolve against the repository root, not this module.
    systemProperty("izmir.root", rootDir.absolutePath)
    maxHeapSize = "2g"
    jvmArgs("-Dstdout.encoding=UTF-8")
    // Declared so the app's asset task can depend on it and Gradle can skip it when nothing changed.
    inputs.dir(rootDir.resolve("research/raw")).withPropertyName("rawData")
    outputs.file(rootDir.resolve("build/izmir-transit.db")).withPropertyName("database")
}

tasks.register<JavaExec>("liveSmoke") {
    description = "Polls the real İztek API once through the repositories, using build/izmir-transit.db."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.aijec.izmirtransit.tools.smoke.LiveSmokeKt")
    jvmArgs("-Dstdout.encoding=UTF-8")
    systemProperty("izmir.root", rootDir.absolutePath)
}

tasks.register<JavaExec>("refreshSmoke") {
    description = "Runs the app's weekly data refresh against the real portal into a temporary database."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.aijec.izmirtransit.tools.smoke.RefreshSmokeKt")
    jvmArgs("-Dstdout.encoding=UTF-8")
    maxHeapSize = "1g"
}

tasks.register<JavaExec>("downloadIzban") {
    description = "Saves the current İZBAN timetable from izban.com.tr into research/raw/izban-web (~4 min)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.aijec.izmirtransit.tools.smoke.IzbanDownloadKt")
    jvmArgs("-Dstdout.encoding=UTF-8")
    systemProperty("izmir.root", rootDir.absolutePath)
}

tasks.register<JavaExec>("planSmoke") {
    description = "Plans a few real trips on build/izmir-transit.db and prints them."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.aijec.izmirtransit.tools.smoke.PlanSmokeKt")
    jvmArgs("-Dstdout.encoding=UTF-8")
    systemProperty("izmir.root", rootDir.absolutePath)
}
