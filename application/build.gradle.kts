// The application's ports (#896, ADR 0002 decision 1, docs/architecture/5-hexagonal-ddd.md T3): the
// repository interface the features use and the event flows next to it. Pure Kotlin like domain:
// no Android, no Room, no Jellyfin SDK. Rule R5 in the architecture module keeps it that way; R9
// keeps the features off `data`, which implements it.
plugins { alias(libs.plugins.kotlin.jvm) }

java { toolchain { languageVersion = Versions.JAVA } }

dependencies {
    implementation(projects.domain)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    // #966: the fakes of the server and the stores, for the repository's tests.
    testImplementation(projects.testing)
    testImplementation(libs.kotlinx.serialization.json)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }

// CI and CLAUDE.md run `./gradlew testDebugUnitTest`. A JVM module has only `test`, so this name
// runs it too.
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs the application tests (the test task)."
    dependsOn(tasks.test)
}
