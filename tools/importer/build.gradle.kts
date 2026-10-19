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
}

tasks.register<JavaExec>("liveSmoke") {
    description = "Polls the real İztek API once through the repositories, using build/izmir-transit.db."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.aijec.izmirtransit.tools.smoke.LiveSmokeKt")
    jvmArgs("-Dstdout.encoding=UTF-8")
    systemProperty("izmir.root", rootDir.absolutePath)
}
